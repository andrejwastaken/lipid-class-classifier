from pathlib import Path

from fastapi import FastAPI
from pydantic import BaseModel

from worker import DEFAULT_ARTIFACT_PATH, DEFAULT_MS_LEVEL, LipidClassifierWorker

app = FastAPI()


class SmokePredictRequest(BaseModel):
    job_id: str
    file_path: str
    user_id: str
    artifact_path: str | None = None
    ms_level: int | None = DEFAULT_MS_LEVEL


@app.get("/")
def root():
    return {"status": "ok"}


@app.post("/smoke/predict")
def smoke_predict(request: SmokePredictRequest):
    artifact_path = Path(request.artifact_path) if request.artifact_path else DEFAULT_ARTIFACT_PATH
    worker = LipidClassifierWorker(artifact_path=artifact_path, ms_level=request.ms_level)
    return worker.process_job(
        {
            "job_id": request.job_id,
            "file_path": request.file_path,
            "user_id": request.user_id,
        }
    )
