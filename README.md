# Lipid Class Classifier - Web Programming and DevOps Project

## Overview

This project is a distributed system for processing and classifying mass spectrometry files (.mzML).  
It uses a microservice-based architecture with asynchronous processing via RabbitMQ.

The system allows users to upload spectral data, which is processed by an ML service and returned as classification results.

---

## Architecture

- React frontend (TypeScript + Tailwind)
- Spring Boot backend (Kotlin)
- FastAPI ML service (Python)
- PostgreSQL database
- RabbitMQ message broker

Flow:

User → React → Spring Boot → PostgreSQL → RabbitMQ → ML Service → PostgreSQL → Spring Boot → React

---

## Core Features

- User authentication (JWT)
- File upload (.mzML spectra)
- Asynchronous ML processing
- Prediction storage and history
- Scalable worker-based architecture

---

## Tech Stack

- Backend: Spring Boot (Kotlin, Java 21)
- ML: Python (FastAPI, scikit-learn)
- Frontend: React (TypeScript, Tailwind CSS)
- DB: PostgreSQL
- Messaging: RabbitMQ
- Deployment: Docker

---

## Running locally

### Docker (Infrastructure)

Start PostgreSQL and RabbitMQ:

```bash
docker-compose up -d
```

### Backend

Run the Spring Boot backend:

```bash
cd backend
mvn spring-boot:run
```

The backend exposes these Part 3 endpoints:

```bash
POST /api/auth/register
POST /api/auth/login
POST /api/jobs/upload       # multipart field: file, requires Bearer token
GET  /api/jobs/{job_id}     # requires Bearer token
```

Uploading an `.mzML` file creates a `PENDING` job row, stores the uploaded file under `UPLOAD_DIR`, and publishes this payload to RabbitMQ queue `ml_jobs`:

```json
{
  "job_id": "...",
  "file_path": "...",
  "user_id": "..."
}
```

### ML Service

Install Python dependencies:

```bash
cd ml-service
python -m venv venv
venv/bin/pip install -r requirements.txt
```

Train the Part 1 m/z-only baseline model from the LipidBlast MSP export:

```bash
ml-service/venv/bin/python ml-service/helpers/msp_converter.py \
  --input data/MoNA-export-LipidBlast.msp \
  --output data/processed/lipidblast_spectra.csv

ml-service/venv/bin/python ml-service/train.py \
  --input data/processed/lipidblast_spectra.csv \
  --output ml-service/artifacts/lipid_class_pipeline.joblib \
  --metadata-output ml-service/artifacts/lipid_class_metadata.json \
  --bin-width 2.0
```

The saved `.joblib` bundle contains the fixed m/z histogram preprocessing, the best baseline classifier, the label encoder, and model metadata. The training pipeline uses only spectrum m/z values as model input.

Run the RabbitMQ/PostgreSQL worker for the full application flow:

```bash
cd ..
set -a
source backend/.env
set +a

ml-service/venv/bin/python ml-service/worker.py \
  --consume \
  --artifact ml-service/artifacts/lipid_class_pipeline.joblib
```

The worker consumes `ml_jobs`, updates `analysis_jobs.status` to `PROCESSING`, runs m/z-only inference from the uploaded `.mzML`, inserts or updates `prediction_results`, then marks the job `DONE`. If inference fails, it marks the job `FAILED` and stores the error message.

Run one local worker inference job without RabbitMQ/DB:

```bash
ml-service/venv/bin/python ml-service/worker.py \
  --artifact ml-service/artifacts/lipid_class_pipeline.joblib \
  --job-json '{"job_id":"local-job-1","file_path":"data/example.mzML","user_id":"local-user-1"}'
```

The worker loads the saved artifact bundle, extracts m/z values from the `.mzML` file with `pyopenms`, and returns a structured result with `status`, `predicted_class`, `probability`, and model metadata.

Optional FastAPI smoke endpoint:

```bash
curl -X POST http://localhost:8000/smoke/predict \
  -H 'Content-Type: application/json' \
  -d '{"job_id":"local-job-1","file_path":"data/example.mzML","user_id":"local-user-1"}'
```

### Frontend

Install dependencies and start the React app:

```bash
cd frontend
npm install
npm run dev
```

The frontend runs on Vite, usually at `http://localhost:5173`. In development it proxies `/api/*` to the Spring Boot backend at `http://localhost:8080`, so no extra browser CORS setup is needed for local testing.

The browser flow:

1. Register or log in through `POST /api/auth/register` or `POST /api/auth/login`.
2. Upload an `.mzML` file through `POST /api/jobs/upload` using the JWT bearer token.
3. Store the returned `job_id`.
4. Poll `GET /api/jobs/{job_id}` every three seconds.
5. Display job status, predicted class, probability, model version, or failure details.

End-to-end status: with Docker infrastructure, backend, frontend, and `worker.py --consume` running, the app authenticates users, uploads `.mzML` files, queues jobs through RabbitMQ, runs ML inference in the Python worker, persists predictions in PostgreSQL, and shows the final prediction screen after polling returns `DONE`.

## Environment Variables

### Backend

Create `backend/.env` with:

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

# Used by docker-compose for the Postgres container
POSTGRES_DB=app_db
POSTGRES_USER=user
POSTGRES_PASSWORD=password
```

Docker Compose reads values from `backend/.env`.

### RabbitMQ Contract

The backend declares a durable queue named `ml_jobs` by default and publishes JSON messages through the RabbitMQ default exchange using the queue name as the routing key. The worker should consume this exact JSON shape:

```json
{
  "job_id": "...",
  "file_path": "...",
  "user_id": "..."
}
```

Publishing is retried with `ML_JOB_PUBLISH_MAX_ATTEMPTS` and `ML_JOB_PUBLISH_RETRY_BACKOFF_MS`; failures are logged and returned as upload errors so failed publish attempts do not silently disappear.

## Status Flow

- `PENDING`: job created
- `PROCESSING`: ML worker running
- `DONE`: prediction stored
- `FAILED`: error occurred

## Notes

This system is designed as an asynchronous, event-driven architecture using a single job queue and the database as the source of truth.
