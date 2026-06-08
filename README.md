# Lipid Class Classifier

Distributed web application for classifying lipid classes from MS/MS `.mzML` spectra.

Users register or log in, upload an `.mzML` file, and receive:

- predicted lipid class
- probability score
- job status and error details when processing fails

The main engineering focus is the ML classification pipeline. The web UI is intentionally simple and exists to demonstrate the complete upload -> queue -> worker -> result flow.

## Current Status

The full Docker flow has been verified locally:

- frontend served at `http://localhost:5173`
- backend API at `http://localhost:8080`
- PostgreSQL and RabbitMQ healthy
- ML worker consuming `ml_jobs`
- upload of a real local `.mzML` file completed with status `DONE`
- smoke prediction returned lipid class `TAG` with probability `0.03578287012126371`

## Architecture

```text
User
  -> React frontend
  -> Spring Boot backend
  -> PostgreSQL job row
  -> RabbitMQ ml_jobs message
  -> Python ML worker
  -> PostgreSQL prediction/result row
  -> Spring Boot polling endpoint
  -> React result screen
```

## Services

### Frontend

Path: `frontend/`

The frontend is a Vite + React app. It owns the browser workflow:

- register/login form
- JWT storage in browser local storage
- `.mzML` file selection and upload
- polling by `job_id`
- result display with job status, predicted class, probability, model version, and error message

In Docker, the frontend is built as static files and served by Nginx. Nginx also proxies `/api/*` to the backend service, so the browser only needs to talk to `http://localhost:5173`.

### Backend

Path: `backend/`

The backend is a Spring Boot Kotlin API. It owns application orchestration:

- JWT authentication
- user persistence
- upload validation for `.mzML`
- saving uploaded files to a shared upload volume
- creating `analysis_jobs` rows
- publishing RabbitMQ messages to `ml_jobs`
- polling endpoint for job/result status

The backend does not perform ML inference. It stores the upload and publishes this queue payload:

```json
{
  "job_id": "...",
  "file_path": "...",
  "user_id": "..."
}
```

### ML Service / Worker

Path: `ml-service/`

The ML service owns spectra parsing, feature engineering, model training, and inference.

Runtime mode is a queue worker:

- loads `ml-service/artifacts/lipid_class_pipeline.joblib`
- consumes messages from RabbitMQ queue `ml_jobs`
- marks the job `PROCESSING`
- parses the uploaded `.mzML` file with `pyopenms`
- extracts only m/z values
- runs the saved scikit-learn pipeline
- writes `prediction_results`
- marks the job `DONE` or `FAILED`

There is also a small FastAPI smoke endpoint in `ml-service/main.py`, but the primary production-style flow is the worker process, not synchronous HTTP inference.

### PostgreSQL

PostgreSQL stores:

- users
- analysis jobs
- prediction results

The database is the source of truth for frontend polling.

### RabbitMQ

RabbitMQ decouples upload from ML inference. The backend publishes jobs to `ml_jobs`, and the Python worker consumes them one at a time.

## ML Pipeline

### Training Data

The current trained metadata was produced from:

```text
data/processed/lipidblast_spectra.csv
```

Current artifact metadata:

- rows: `485,796`
- lipid classes: `56`
- random state: `42`
- trained at: `2026-06-07T21:27:45Z`
- artifact version: `1`

The source file is generated from the LipidBlast MSP export with:

```bash
ml-service/venv/bin/python ml-service/helpers/msp_converter.py \
  --input data/MoNA-export-LipidBlast.msp \
  --output data/processed/lipidblast_spectra.csv
```

### Input Constraint

The ML implementation intentionally uses only m/z values as model input features. Intensities, precursor information, retention time, sample metadata, and filenames are not model features.

For inference, `.mzML` parsing is done with `pyopenms`.

### Feature Engineering

The reusable featureizer is `MzHistogramFeaturizer` in `ml-service/ml_core/features.py`.

Current artifact settings:

- feature type: fixed m/z histogram
- min m/z: `0.0`
- max m/z: `2000.0`
- bin width: `2.0`
- normalization: enabled
- input values: m/z only

Each spectrum becomes a fixed-length histogram over the m/z range. This gives Logistic Regression and Random Forest a deterministic vector representation and keeps preprocessing reusable for inference.

### Models

Only baseline models are currently in scope:

- Logistic Regression
- Random Forest

Training happens in `ml-service/train.py`. The script:

1. loads `lipid_class` and `mz_values`
2. label-encodes lipid classes
3. creates a deterministic train/test split
4. trains both baseline pipelines
5. evaluates accuracy and macro F1
6. chooses the best model by macro F1, then accuracy
7. saves one artifact bundle with preprocessing, model, label encoder, and metadata

Current evaluation:

| Model | Accuracy | Macro F1 |
| --- | ---: | ---: |
| Logistic Regression | 0.5504 | 0.3828 |
| Random Forest | 0.6488 | 0.5724 |

The current best model is `random_forest`.

### Artifact Bundle

The generated artifact is:

```text
ml-service/artifacts/lipid_class_pipeline.joblib
```

It is intentionally ignored by Git because it is a generated binary model artifact. Regenerate it from the training command or distribute it through Git LFS, release assets, or external storage.

The small tracked metadata file is:

```text
ml-service/artifacts/lipid_class_metadata.json
```

## Run The Full App With Docker

Prerequisites:

- Docker with Compose support
- trained model artifact at `ml-service/artifacts/lipid_class_pipeline.joblib`

Start the full stack:

```bash
docker compose up --build
```

Open:

```text
http://localhost:5173
```

RabbitMQ UI:

```text
http://localhost:15672
```

RabbitMQ default login:

```text
guest / guest
```

Stop the stack:

```bash
docker compose down
```

Reset database, RabbitMQ, and uploaded files:

```bash
docker compose down -v
```

## Local Smoke Test

After `docker compose up --build`, use the browser:

1. Open `http://localhost:5173`.
2. Register a user.
3. Upload an `.mzML` file from `data/mzML-Diabetes-CKD1-Neg-mzml 1/`.
4. Wait for status to move from `PENDING` to `PROCESSING` to `DONE`.
5. Confirm predicted class and probability appear.

API smoke flow:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"smoke@example.com","password":"password123"}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')

curl -X POST http://localhost:8080/api/jobs/upload \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@data/mzML-Diabetes-CKD1-Neg-mzml 1/4-10-2025_0375_8_1_887.mzML;filename=smoke.mzML"
```

Use the returned `job_id`:

```bash
curl http://localhost:8080/api/jobs/<job_id> \
  -H "Authorization: Bearer $TOKEN"
```

## Train The Model

Create a virtual environment:

```bash
cd ml-service
python -m venv venv
venv/bin/pip install -r requirements.txt
cd ..
```

Convert MSP to CSV:

```bash
ml-service/venv/bin/python ml-service/helpers/msp_converter.py \
  --input data/MoNA-export-LipidBlast.msp \
  --output data/processed/lipidblast_spectra.csv
```

Train and compare baselines:

```bash
ml-service/venv/bin/python ml-service/train.py \
  --input data/processed/lipidblast_spectra.csv \
  --output ml-service/artifacts/lipid_class_pipeline.joblib \
  --metadata-output ml-service/artifacts/lipid_class_metadata.json \
  --bin-width 2.0
```

## Run Services Without Full Docker

Start only infrastructure:

```bash
docker compose up -d postgres rabbitmq
```

Backend:

```bash
cd backend
mvn spring-boot:run
```

ML worker:

```bash
set -a
source backend/.env
set +a

ml-service/venv/bin/python ml-service/worker.py \
  --consume \
  --artifact ml-service/artifacts/lipid_class_pipeline.joblib
```

Frontend:

```bash
cd frontend
npm install
npm run dev
```

## API Contract

### Auth

```text
POST /api/auth/register
POST /api/auth/login
```

Request:

```json
{
  "email": "student@example.com",
  "password": "password123"
}
```

Response:

```json
{
  "token": "...",
  "user": {
    "id": "...",
    "email": "student@example.com"
  }
}
```

### Upload

```text
POST /api/jobs/upload
Authorization: Bearer <token>
Content-Type: multipart/form-data
field: file
```

Response:

```json
{
  "job_id": "...",
  "status": "PENDING"
}
```

### Poll Result

```text
GET /api/jobs/{job_id}
Authorization: Bearer <token>
```

Response:

```json
{
  "job_id": "...",
  "status": "DONE",
  "original_filename": "sample.mzML",
  "predicted_class": "TAG",
  "probability": 0.03578287012126371,
  "model_version": "1",
  "error_message": null,
  "created_at": "...",
  "updated_at": "..."
}
```

## Messaging Contract

Queue:

```text
ml_jobs
```

Message:

```json
{
  "job_id": "...",
  "file_path": "...",
  "user_id": "..."
}
```

Status lifecycle:

- `PENDING`: backend created the job and published queue message
- `PROCESSING`: worker started inference
- `DONE`: worker wrote prediction result
- `FAILED`: backend or worker stored an error

## Environment Variables

Docker Compose wires local defaults directly. For manual local runs, create `backend/.env`:

```env
DB_URL=jdbc:postgresql://localhost:5432/app_db
DB_USER=user
DB_PASSWORD=password
JWT_SECRET=9b3f4a1d8c0e6f2b7a5d1c9e4f8b2a6d0c7e3f1a9b5d2c8e6f4a1b7d3c9e2f5
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USER=guest
RABBITMQ_PASSWORD=guest
UPLOAD_DIR=uploads
ML_JOBS_QUEUE=ml_jobs
ML_JOB_PUBLISH_MAX_ATTEMPTS=3
ML_JOB_PUBLISH_RETRY_BACKOFF_MS=250
POSTGRES_DB=app_db
POSTGRES_USER=user
POSTGRES_PASSWORD=password
```

## Troubleshooting

### Worker Fails Because Artifact Is Missing

Symptom:

```text
FileNotFoundError: lipid_class_pipeline.joblib
```

Fix: train the model or place the artifact at:

```text
ml-service/artifacts/lipid_class_pipeline.joblib
```

### Worker Fails Loading pyopenms Native Libraries

The Docker image includes the required runtime libraries. If running locally outside Docker, install dependencies in a Python version supported by `pyopenms` and prefer the documented virtual environment.

### Upload Stays PENDING

Check the worker and RabbitMQ:

```bash
docker compose logs -f ml-worker
docker compose logs -f rabbitmq
```

The worker should print:

```text
Consuming RabbitMQ queue ml_jobs at rabbitmq:5672
```

### Backend Cannot Connect To Database

Check:

```bash
docker compose ps
docker compose logs postgres
docker compose logs backend
```

In Docker, the backend uses:

```text
jdbc:postgresql://postgres:5432/app_db
```

For manual host runs, use:

```text
jdbc:postgresql://localhost:5432/app_db
```

### Frontend Cannot Reach API

In Docker, open the app through:

```text
http://localhost:5173
```

Nginx proxies `/api/*` to the backend. In Vite development, `vite.config.ts` proxies `/api` to `http://localhost:8080`.

### DockerHub Publish Fails In CI

Confirm GitHub secrets exist:

```text
DOCKERHUB_USERNAME
DOCKERHUB_TOKEN
```

Also confirm the DockerHub token has write permission.
