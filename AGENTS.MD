# AGENTS.md

## Project Overview

This project is a Lipid Class Classifier based on MS/MS spectra.

Goal:

- Users upload .mzML files
- System returns predicted lipid class + probability

IMPORTANT:

- Main focus is ML classification accuracy
- Web app is secondary

---

## ML Constraints (STRICT)

- Use ONLY the `mz` column from spectra
- Convert mzML → DataFrame using `pyopenms`
- Start with simple models:
  - Logistic Regression
  - Random Forest
- CNN/Transformers are optional later

- Preprocessing must be reusable in inference
- Save model + preprocessing together (pipeline or equivalent)

---

## System Architecture

This is an ASYNCHRONOUS system:

1. User uploads file
2. Backend creates job (status = PENDING)
3. Backend sends job to RabbitMQ
4. Python worker processes job
5. Worker stores result in PostgreSQL
6. Frontend polls for result

---

## Service Responsibilities

### ml-service (Python)

- mzML parsing (pyopenms)
- preprocessing
- model inference
- worker that consumes RabbitMQ messages

DO NOT:

- implement auth
- implement REST business logic

---

### backend (Spring Boot Kotlin)

- authentication (JWT)
- file upload handling
- job creation and tracking
- communication with RabbitMQ
- database access

DO NOT:

- implement ML logic

---

### frontend (React)

- upload UI
- display prediction results
- authentication UI

---

## Data Model (Conceptual)

Job:

- id (UUID)
- user_id
- file_path
- status (PENDING, PROCESSING, DONE, FAILED)
- result (class, probability)

---

## Messaging (RabbitMQ)

Queue: `ml_jobs`

Message format:

```json
{
	"job_id": "...",
	"file_path": "...",
	"user_id": "..."
}
```

## General Rules for Agent

Do NOT introduce unnecessary complexity
Prefer simple, working solutions first
Keep services decoupled
Follow defined architecture strictly
