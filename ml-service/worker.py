import argparse
import json
import os
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Protocol
from urllib.parse import urlparse
from uuid import uuid4

from ml_core.artifacts import load_artifact_bundle, predict_mzml_file


DEFAULT_ARTIFACT_PATH = Path(__file__).resolve().parent / "artifacts" / "lipid_class_pipeline.joblib"
DEFAULT_MS_LEVEL = 2
DEFAULT_QUEUE_NAME = "ml_jobs"


class JobConsumer(Protocol):
    def __call__(self, handle_job: Callable[[Mapping[str, Any]], Dict[str, Any]]) -> None:
        ...


@dataclass(frozen=True)
class JobPayload:
    job_id: str
    file_path: Path
    user_id: str

    @classmethod
    def from_mapping(cls, payload: Mapping[str, Any]) -> "JobPayload":
        missing = [key for key in ("job_id", "file_path", "user_id") if not payload.get(key)]
        if missing:
            raise ValueError(f"Missing required job payload fields: {', '.join(missing)}")

        return cls(
            job_id=str(payload["job_id"]),
            file_path=Path(str(payload["file_path"])),
            user_id=str(payload["user_id"]),
        )


class LipidClassifierWorker:
    def __init__(self, artifact_path: Path = DEFAULT_ARTIFACT_PATH, ms_level: int | None = DEFAULT_MS_LEVEL) -> None:
        self.artifact_path = artifact_path
        self.ms_level = ms_level
        self.bundle = load_artifact_bundle(artifact_path)

    def process_job(self, payload: Mapping[str, Any]) -> Dict[str, Any]:
        try:
            job = JobPayload.from_mapping(payload)
            prediction = predict_mzml_file(self.bundle, job.file_path, ms_level=self.ms_level)
            metadata = self.bundle.get("metadata", {})

            return {
                "job_id": job.job_id,
                "user_id": job.user_id,
                "status": "DONE",
                "predicted_class": prediction["predicted_class"],
                "probability": prediction["probability"],
                "top_predictions": prediction.get(
                    "top_predictions",
                    [
                        {
                            "class_name": prediction["predicted_class"],
                            "probability": prediction["probability"],
                        }
                    ],
                ),
                "model": {
                    "artifact_path": str(self.artifact_path),
                    "artifact_version": metadata.get("artifact_version"),
                    "best_model": metadata.get("best_model"),
                },
                "error": None,
            }
        except Exception as exc:
            job_id = str(payload.get("job_id", "")) if isinstance(payload, Mapping) else ""
            user_id = str(payload.get("user_id", "")) if isinstance(payload, Mapping) else ""
            return {
                "job_id": job_id,
                "user_id": user_id,
                "status": "FAILED",
                "predicted_class": None,
                "probability": None,
                "top_predictions": [],
                "model": {
                    "artifact_path": str(self.artifact_path),
                    "artifact_version": self.bundle.get("metadata", {}).get("artifact_version"),
                    "best_model": self.bundle.get("metadata", {}).get("best_model"),
                },
                "error": {
                    "type": exc.__class__.__name__,
                    "message": str(exc),
                },
            }


@dataclass(frozen=True)
class PostgresSettings:
    host: str = "localhost"
    port: int = 5432
    database: str = "app_db"
    user: str = "user"
    password: str = "password"

    @classmethod
    def from_env(cls) -> "PostgresSettings":
        jdbc_url = os.getenv("DB_URL", "")
        parsed_jdbc = _parse_jdbc_postgres_url(jdbc_url)

        return cls(
            host=os.getenv("PGHOST")
            or os.getenv("POSTGRES_HOST")
            or parsed_jdbc.get("host")
            or "localhost",
            port=int(os.getenv("PGPORT") or os.getenv("POSTGRES_PORT") or parsed_jdbc.get("port") or 5432),
            database=os.getenv("PGDATABASE")
            or os.getenv("POSTGRES_DB")
            or os.getenv("POSTGRES_DATABASE")
            or parsed_jdbc.get("database")
            or "app_db",
            user=os.getenv("PGUSER") or os.getenv("POSTGRES_USER") or os.getenv("DB_USER") or "user",
            password=os.getenv("PGPASSWORD")
            or os.getenv("POSTGRES_PASSWORD")
            or os.getenv("DB_PASSWORD")
            or "password",
        )

    def connection_kwargs(self) -> Dict[str, Any]:
        return {
            "host": self.host,
            "port": self.port,
            "dbname": self.database,
            "user": self.user,
            "password": self.password,
        }


@dataclass(frozen=True)
class RabbitMqSettings:
    host: str = "localhost"
    port: int = 5672
    username: str = "guest"
    password: str = "guest"
    queue_name: str = DEFAULT_QUEUE_NAME

    @classmethod
    def from_env(cls) -> "RabbitMqSettings":
        return cls(
            host=os.getenv("RABBITMQ_HOST", "localhost"),
            port=int(os.getenv("RABBITMQ_PORT", "5672")),
            username=os.getenv("RABBITMQ_USER", "guest"),
            password=os.getenv("RABBITMQ_PASSWORD", "guest"),
            queue_name=os.getenv("ML_JOBS_QUEUE", DEFAULT_QUEUE_NAME),
        )


def _parse_jdbc_postgres_url(value: str) -> Dict[str, str]:
    if not value.startswith("jdbc:postgresql://"):
        return {}

    parsed = urlparse(value.removeprefix("jdbc:"))
    result = {
        "host": parsed.hostname or "",
        "port": str(parsed.port or ""),
        "database": parsed.path.lstrip("/"),
    }
    return {key: current for key, current in result.items() if current}


def persist_result_to_postgres(result: Dict[str, Any], settings: PostgresSettings | None = None) -> None:
    import psycopg

    settings = settings or PostgresSettings.from_env()
    with psycopg.connect(**settings.connection_kwargs()) as connection:
        write_prediction_result(connection, result)


def mark_job_processing_in_postgres(payload: Mapping[str, Any], settings: PostgresSettings | None = None) -> None:
    import psycopg

    settings = settings or PostgresSettings.from_env()
    with psycopg.connect(**settings.connection_kwargs()) as connection:
        mark_job_processing(connection, payload)


def mark_job_processing(connection: Any, payload: Mapping[str, Any]) -> None:
    job_id = payload.get("job_id")
    if not job_id:
        return

    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE analysis_jobs
            SET status = %s, error_message = NULL, updated_at = now()
            WHERE id = %s
            """,
            ("PROCESSING", str(job_id)),
        )
    connection.commit()


def write_prediction_result(connection: Any, result: Dict[str, Any]) -> None:
    job_id = result.get("job_id")
    if not job_id:
        return

    if result.get("status") == "DONE":
        model = result.get("model") or {}
        model_version = str(
            model.get("artifact_version")
            or model.get("best_model")
            or model.get("artifact_path")
            or "unknown"
        )
        top_predictions = result.get("top_predictions") or [
            {
                "class_name": result["predicted_class"],
                "probability": result["probability"],
            }
        ]
        top_predicted_classes = ",".join(str(item["class_name"]) for item in top_predictions[:5])
        top_probabilities = ",".join(str(float(item["probability"])) for item in top_predictions[:5])
        with connection.cursor() as cursor:
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = %s, error_message = NULL, updated_at = now()
                WHERE id = %s
                """,
                ("PROCESSING", job_id),
            )
            cursor.execute(
                """
                INSERT INTO prediction_results
                    (
                        id, job_id, predicted_class, probability, model_version,
                        top_predicted_classes, top_probabilities, created_at
                    )
                VALUES
                    (%s, %s, %s, %s, %s, %s, %s, now())
                ON CONFLICT (job_id) DO UPDATE
                SET predicted_class = EXCLUDED.predicted_class,
                    probability = EXCLUDED.probability,
                    model_version = EXCLUDED.model_version,
                    top_predicted_classes = EXCLUDED.top_predicted_classes,
                    top_probabilities = EXCLUDED.top_probabilities,
                    created_at = now()
                """,
                (
                    str(uuid4()),
                    job_id,
                    result["predicted_class"],
                    result["probability"],
                    model_version,
                    top_predicted_classes,
                    top_probabilities,
                ),
            )
            cursor.execute(
                """
                UPDATE analysis_jobs
                SET status = %s, error_message = NULL, updated_at = now()
                WHERE id = %s
                """,
                ("DONE", job_id),
            )
        connection.commit()
        return

    error = result.get("error") or {}
    error_message = error.get("message") or "ML worker failed to process this job"
    with connection.cursor() as cursor:
        cursor.execute(
            """
            UPDATE analysis_jobs
            SET status = %s, error_message = %s, updated_at = now()
            WHERE id = %s
            """,
            ("FAILED", error_message[:1000], job_id),
        )
    connection.commit()


def consume_rabbitmq_jobs(settings: RabbitMqSettings | None = None) -> JobConsumer:
    import pika

    settings = settings or RabbitMqSettings.from_env()

    def consume_jobs(handle_job: Callable[[Mapping[str, Any]], Dict[str, Any]]) -> None:
        credentials = pika.PlainCredentials(settings.username, settings.password)
        parameters = pika.ConnectionParameters(
            host=settings.host,
            port=settings.port,
            credentials=credentials,
            heartbeat=60,
            blocked_connection_timeout=300,
        )
        connection = pika.BlockingConnection(parameters)
        channel = connection.channel()
        channel.queue_declare(queue=settings.queue_name, durable=True)
        channel.basic_qos(prefetch_count=1)

        def on_message(ch: Any, method: Any, properties: Any, body: bytes) -> None:
            try:
                payload = json.loads(body.decode("utf-8"))
                handle_job(payload)
                ch.basic_ack(delivery_tag=method.delivery_tag)
            except Exception:
                ch.basic_nack(delivery_tag=method.delivery_tag, requeue=False)
                raise

        channel.basic_consume(queue=settings.queue_name, on_message_callback=on_message)
        print(f"Consuming RabbitMQ queue {settings.queue_name} at {settings.host}:{settings.port}", flush=True)
        channel.start_consuming()

    return consume_jobs


def run_worker_loop(
    worker: LipidClassifierWorker,
    consume_jobs: JobConsumer,
    handle_result: Callable[[Dict[str, Any]], None],
    handle_started: Callable[[Mapping[str, Any]], None] | None = None,
) -> None:
    def handle_job(payload: Mapping[str, Any]) -> Dict[str, Any]:
        if handle_started is not None:
            handle_started(payload)
        result = worker.process_job(payload)
        handle_result(result)
        return result

    consume_jobs(handle_job)


def _load_payload(args: argparse.Namespace) -> Dict[str, Any]:
    if args.job_json:
        return json.loads(args.job_json)
    if args.job_file:
        return json.loads(args.job_file.read_text(encoding="utf-8"))
    return json.load(sys.stdin)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run one m/z-only lipid classification worker job.")
    parser.add_argument("--artifact", type=Path, default=DEFAULT_ARTIFACT_PATH)
    parser.add_argument("--ms-level", type=int, default=DEFAULT_MS_LEVEL)
    parser.add_argument("--consume", action="store_true", help="Consume RabbitMQ jobs and persist results to PostgreSQL.")
    parser.add_argument("--job-json", help="Inline JSON payload with job_id, file_path, and user_id.")
    parser.add_argument("--job-file", type=Path, help="Path to a JSON payload file.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    worker = LipidClassifierWorker(artifact_path=args.artifact, ms_level=args.ms_level)

    if args.consume or (not args.job_json and not args.job_file and sys.stdin.isatty()):
        run_worker_loop(worker, consume_rabbitmq_jobs(), persist_result_to_postgres, mark_job_processing_in_postgres)
        return

    result = worker.process_job(_load_payload(args))
    print(json.dumps(result, indent=2))
    if result["status"] == "FAILED":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
