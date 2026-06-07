import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import worker as worker_module
from worker import JobPayload, LipidClassifierWorker, run_worker_loop


def test_job_payload_requires_stable_queue_contract() -> None:
    payload = JobPayload.from_mapping(
        {
            "job_id": "job-1",
            "file_path": "/tmp/sample.mzML",
            "user_id": "user-1",
        }
    )

    assert payload.job_id == "job-1"
    assert payload.file_path == Path("/tmp/sample.mzML")
    assert payload.user_id == "user-1"


def test_job_payload_rejects_missing_required_fields() -> None:
    with pytest.raises(ValueError, match="file_path"):
        JobPayload.from_mapping({"job_id": "job-1", "user_id": "user-1"})


def test_worker_returns_structured_prediction(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    artifact_path = tmp_path / "bundle.joblib"

    monkeypatch.setattr(
        worker_module,
        "load_artifact_bundle",
        lambda path: {
            "metadata": {
                "artifact_version": 1,
                "best_model": "logistic_regression",
            }
        },
    )
    monkeypatch.setattr(
        worker_module,
        "predict_mzml_file",
        lambda bundle, path, ms_level=None: {
            "predicted_class": "PC",
            "probability": 0.87,
        },
    )

    worker = LipidClassifierWorker(artifact_path=artifact_path, ms_level=2)
    result = worker.process_job(
        {
            "job_id": "job-1",
            "file_path": str(tmp_path / "sample.mzML"),
            "user_id": "user-1",
        }
    )

    assert result == {
        "job_id": "job-1",
        "user_id": "user-1",
        "status": "DONE",
        "predicted_class": "PC",
        "probability": 0.87,
        "model": {
            "artifact_path": str(artifact_path),
            "artifact_version": 1,
            "best_model": "logistic_regression",
        },
        "error": None,
    }


def test_worker_returns_failed_result_for_invalid_payload(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(worker_module, "load_artifact_bundle", lambda path: {"metadata": {"artifact_version": 1}})

    worker = LipidClassifierWorker(artifact_path=tmp_path / "bundle.joblib")
    result = worker.process_job({"job_id": "job-1", "user_id": "user-1"})

    assert result["job_id"] == "job-1"
    assert result["user_id"] == "user-1"
    assert result["status"] == "FAILED"
    assert result["predicted_class"] is None
    assert result["probability"] is None
    assert result["error"]["type"] == "ValueError"
    assert "file_path" in result["error"]["message"]


def test_run_worker_loop_keeps_queue_integration_pluggable(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    monkeypatch.setattr(worker_module, "load_artifact_bundle", lambda path: {"metadata": {}})
    monkeypatch.setattr(
        worker_module,
        "predict_mzml_file",
        lambda bundle, path, ms_level=None: {
            "predicted_class": "TG",
            "probability": 0.66,
        },
    )

    worker = LipidClassifierWorker(artifact_path=tmp_path / "bundle.joblib")
    captured_results = []

    def consume_jobs(handle_job):
        handle_job({"job_id": "job-1", "file_path": "/tmp/sample.mzML", "user_id": "user-1"})

    run_worker_loop(worker, consume_jobs, captured_results.append)

    assert captured_results[0]["status"] == "DONE"
    assert captured_results[0]["predicted_class"] == "TG"
