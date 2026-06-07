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

Run the FastAPI ML service:

```bash
cd ml-service
uvicorn main:app --reload
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

Run one local worker inference job from the queue payload contract:

```bash
ml-service/venv/bin/python ml-service/worker.py \
  --artifact ml-service/artifacts/lipid_class_pipeline.joblib \
  --job-json '{"job_id":"local-job-1","file_path":"data/example.mzML","user_id":"local-user-1"}'
```

The worker loads the saved artifact bundle, extracts m/z values from the `.mzML` file with `pyopenms`, and returns a structured result with `status`, `predicted_class`, `probability`, and model metadata. Queue consumption is intentionally pluggable for Part 2; RabbitMQ wiring is added in the later message-system phases.

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
npm start
```

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

# Used by docker-compose for the Postgres container
POSTGRES_DB=app_db
POSTGRES_USER=user
POSTGRES_PASSWORD=password
```

Docker Compose reads values from `backend/.env`.

## Status Flow

- `PENDING`: job created
- `PROCESSING`: ML worker running
- `DONE`: prediction stored
- `FAILED`: error occurred

## Notes

This system is designed as an asynchronous, event-driven architecture using a single job queue and the database as the source of truth.
