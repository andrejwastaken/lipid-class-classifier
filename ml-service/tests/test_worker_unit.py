"""Edge-case unit tests for the ML worker.

`test_worker.py` already covers the happy paths of `JobPayload.from_mapping`, the DONE and
FAILED lifecycles and the PROCESSING transition. This module targets the boundaries around
them: falsy field values, absent optional data, fallbacks and truncation.

`FakeConnection` is reused from `test_worker.py` rather than duplicated.
"""

from pathlib import Path
from uuid import UUID

import pytest

from test_worker import FakeConnection
from worker import JobPayload, mark_job_processing, write_prediction_result

JOB_ID = "78bbf90b-9081-4f65-8a8e-d7d12e02bf01"


def test_job_payload_reports_every_missing_field_at_once() -> None:
    with pytest.raises(ValueError) as excinfo:
        JobPayload.from_mapping({})

    message = str(excinfo.value)
    assert "job_id" in message
    assert "file_path" in message
    assert "user_id" in message


@pytest.mark.parametrize("falsy", ["", None, 0])
def test_job_payload_rejects_falsy_field_values(falsy: object) -> None:
    # The guard is `not payload.get(key)`, so any falsy value counts as missing - including a
    # numeric 0, which a producer could plausibly send as a user_id.
    payload = {"job_id": "job-1", "file_path": "/tmp/sample.mzML", "user_id": falsy}

    with pytest.raises(ValueError, match="user_id"):
        JobPayload.from_mapping(payload)


def test_job_payload_coerces_non_string_fields_to_strings() -> None:
    job_uuid = UUID(JOB_ID)

    payload = JobPayload.from_mapping(
        {"job_id": job_uuid, "file_path": Path("/tmp/sample.mzML"), "user_id": 42}
    )

    assert payload.job_id == JOB_ID
    assert payload.user_id == "42"
    assert payload.file_path == Path("/tmp/sample.mzML")


def test_job_payload_ignores_unknown_extra_fields() -> None:
    payload = JobPayload.from_mapping(
        {
            "job_id": "job-1",
            "file_path": "/tmp/sample.mzML",
            "user_id": "user-1",
            "priority": "high",
        }
    )

    assert payload.job_id == "job-1"


def test_write_prediction_result_without_job_id_is_a_no_op() -> None:
    connection = FakeConnection()

    write_prediction_result(connection, {"status": "DONE", "predicted_class": "PC"})

    assert connection.executed == []
    assert connection.commits == 0


def test_write_prediction_result_falls_back_to_the_single_prediction() -> None:
    connection = FakeConnection()

    write_prediction_result(
        connection,
        {
            "job_id": JOB_ID,
            "status": "DONE",
            "predicted_class": "TG",
            "probability": 0.42,
            "top_predictions": [],
            "model": {"artifact_version": 7},
        },
    )

    insert_params = connection.executed[1][1]
    assert insert_params[2:] == ("TG", 0.42, "7", "TG", "0.42")


def test_write_prediction_result_keeps_at_most_five_top_predictions() -> None:
    connection = FakeConnection()

    write_prediction_result(
        connection,
        {
            "job_id": JOB_ID,
            "status": "DONE",
            "predicted_class": "PC",
            "probability": 0.5,
            "top_predictions": [
                {"class_name": f"C{index}", "probability": 0.1} for index in range(8)
            ],
            "model": {"artifact_version": 1},
        },
    )

    top_classes = connection.executed[1][1][5]
    top_probabilities = connection.executed[1][1][6]
    assert top_classes == "C0,C1,C2,C3,C4"
    assert len(top_probabilities.split(",")) == 5


@pytest.mark.parametrize(
    ("model", "expected_version"),
    [
        ({"artifact_version": 3, "best_model": "random_forest"}, "3"),
        ({"best_model": "random_forest"}, "random_forest"),
        ({"artifact_path": "/app/artifacts/bundle.joblib"}, "/app/artifacts/bundle.joblib"),
        ({}, "unknown"),
    ],
)
def test_write_prediction_result_model_version_fallback_chain(
    model: dict, expected_version: str
) -> None:
    connection = FakeConnection()

    write_prediction_result(
        connection,
        {
            "job_id": JOB_ID,
            "status": "DONE",
            "predicted_class": "PC",
            "probability": 0.5,
            "top_predictions": [{"class_name": "PC", "probability": 0.5}],
            "model": model,
        },
    )

    assert connection.executed[1][1][4] == expected_version


def test_write_prediction_result_failed_without_error_uses_a_default_message() -> None:
    connection = FakeConnection()

    write_prediction_result(connection, {"job_id": JOB_ID, "status": "FAILED"})

    status, message, job_id = connection.executed[0][1]
    assert status == "FAILED"
    assert message == "ML worker failed to process this job"
    assert job_id == JOB_ID


def test_write_prediction_result_truncates_long_error_messages() -> None:
    connection = FakeConnection()

    write_prediction_result(
        connection,
        {"job_id": JOB_ID, "status": "FAILED", "error": {"message": "x" * 5000}},
    )

    assert len(connection.executed[0][1][1]) == 1000


def test_write_prediction_result_treats_unknown_status_as_failure() -> None:
    connection = FakeConnection()

    write_prediction_result(connection, {"job_id": JOB_ID, "status": "PROCESSING"})

    # Anything that is not DONE takes the failure branch.
    assert connection.executed[0][1][0] == "FAILED"


def test_mark_job_processing_without_job_id_is_a_no_op() -> None:
    connection = FakeConnection()

    mark_job_processing(connection, {})

    assert connection.executed == []
    assert connection.commits == 0
