import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Dict, Mapping, Protocol

from ml_core.artifacts import load_artifact_bundle, predict_mzml_file


DEFAULT_ARTIFACT_PATH = Path(__file__).resolve().parent / "artifacts" / "lipid_class_pipeline.joblib"
DEFAULT_MS_LEVEL = 2


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


def run_worker_loop(
    worker: LipidClassifierWorker,
    consume_jobs: JobConsumer,
    handle_result: Callable[[Dict[str, Any]], None],
) -> None:
    def handle_job(payload: Mapping[str, Any]) -> Dict[str, Any]:
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
    parser.add_argument("--job-json", help="Inline JSON payload with job_id, file_path, and user_id.")
    parser.add_argument("--job-file", type=Path, help="Path to a JSON payload file.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    worker = LipidClassifierWorker(artifact_path=args.artifact, ms_level=args.ms_level)
    result = worker.process_job(_load_payload(args))
    print(json.dumps(result, indent=2))
    if result["status"] == "FAILED":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
