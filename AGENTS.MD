# AGENTS.md

## Mission

Build a Lipid Class Classifier for MS/MS spectra where users upload .mzML files and receive:

- predicted lipid class
- probability score

Main focus is ML classification quality. Web app polish is secondary.

## Current Stack Snapshot (as of April 2026)

- backend: Spring Boot Kotlin project exists but is mostly scaffold.
- frontend: Vite + React starter template, not domain implementation.
- ml-service: minimal FastAPI stub and helper utilities; no production training/inference pipeline.
- infra: docker-compose has PostgreSQL and RabbitMQ.
- notes define phased plan where Part 1 = ML core and Part 2 = ML inference worker setup.

## Data And Existing Helpers

- test data is available in the `data/` directory (including `.mzML` and related `.csv` files).
- an existing helper for mzML conversion is present in `ml-service/helpers/` and can be reused/adapted for mzML -> CSV/DataFrame workflows.

## STRICT ML Constraints

- Use ONLY the `mz` values from spectra as model input features.
- Convert mzML to DataFrame using `pyopenms`.
- Start with simple baseline models only:
  - Logistic Regression
  - Random Forest
- CNN/Transformers are explicitly out of scope for now.
- Preprocessing must be reusable in inference.
- Save model + preprocessing together as one artifact bundle.

## Phase Deliverables

Use these deliverables when asked to implement one or more parts/phases. If a user asks for specific phases, implement only those phases. If not specified, ask before implementing large cross-service scope.

### Part 0 (Domain and data research)

- review local dataset structure and helper files.
- review referenced domain resources (for lipid class context and spectra conventions).
- summarize findings into practical engineering assumptions.
- produce a clear Codex-ready implementation prompt when requested.

### Part 1 (ML core)

- training dataset loader from local files.
- mzML parser using pyopenms that extracts only mz data.
- deterministic featureization strategy based on mz (for example fixed bins/histogram).
- reusable sklearn preprocessing + model pipeline.
- train and compare Logistic Regression and Random Forest.
- output metrics (at least accuracy and macro F1).
- persist best pipeline artifact (single file, e.g. joblib).
- persist label mapping and model metadata/version.

### Part 2 (ML inference worker setup)

- Python worker process (non-API primary mode).
- worker loads persisted artifact bundle.
- worker receives a job payload with `job_id`, `file_path`, `user_id`.
- worker runs mzML -> features -> predict class + probability.
- worker returns structured result object for later DB write.
- keep queue integration pluggable/simple (real RabbitMQ wiring can be minimal stub if not requested, but worker interface must be ready for queue consumption).
- optional FastAPI endpoints for local smoke testing only.

### Part 3 (Backend + auth + DB foundation)

- implement JWT authentication in Spring Boot.
- configure PostgreSQL integration.
- implement core entities and persistence for users, jobs, and predictions/results.
- implement upload endpoint that creates job with status PENDING.
- persist job metadata and publish queue message for processing.

### Part 4 (Message system)

- configure RabbitMQ messaging in backend.
- define queue `ml_jobs` and stable payload contract.
- implement publisher for job events/messages from backend.
- add minimal resilience for publish failures (retry/error logging as appropriate).

### Part 5 (Worker execution layer)

- consume queue messages from `ml_jobs`.
- run ML inference for each job payload.
- write prediction result to DB (or via integration contract when direct DB write is deferred).
- update job status lifecycle: PROCESSING -> DONE/FAILED.
- emit useful error details for failed jobs.

### Part 6 (Frontend)

- implement file upload flow returning `job_id`.
- implement auth login UI flow.
- add polling to retrieve job result by `job_id`.
- display predicted class, probability, and job status/error.

### Part 7 (Dockerization)

- containerize backend, frontend, and ml-service.
- include infrastructure services (PostgreSQL and RabbitMQ).
- provide one docker-compose setup for local full-stack run.
- ensure environment variables are documented and wired.

### Part 8 (CI/CD)

- This part is for the DevOps project tied to this application. Do not implement it unless explicitly requested.
- Treat the Lipid Class Classifier as the selected ready application with at least three services, including a database:
  - frontend
  - backend
  - ml-service/worker
  - PostgreSQL database
  - RabbitMQ if message orchestration is included
- Required DevOps deliverables:
  - public git repository for the application.
  - Dockerize the application services.
  - orchestrate the application and database with Docker Compose.
  - choose one CI platform (GitHub Actions, GitLab CI, Jenkins, etc.).
  - set up a CI or CI/CD pipeline where a git push builds and publishes the new Docker image version to a registry such as DockerHub.
  - optional bonus: add CD that deploys to a deployment environment using Docker orchestration, Kubernetes, Argo CD, or similar.
- Required Kubernetes deliverables:
  - Deployment for the application with required ConfigMaps/Secrets.
  - Service for the application.
  - Ingress for the application.
  - StatefulSet for the database with required ConfigMaps/Secrets.
  - place manifests in a separate namespace on the user's cluster and demonstrate that it works.
- See `notes/devops-ci-cd-requirements.md` for the grading breakdown and implementation assumptions.

### Part 9 (Polish)

- complete top-level README with local run and architecture steps.
- document API and messaging contracts.
- provide concise troubleshooting notes for common local failures.

### Part 10 (Presentation)

- prepare demo flow script (upload -> processing -> result).
- prepare concise summary of architecture decisions and ML results.
- include clearly stated contribution/bonus items when requested.

## System Architecture (target)

1. User uploads file.
2. Backend creates job with status PENDING.
3. Backend publishes job to RabbitMQ (`ml_jobs`).
4. Python worker consumes job and performs inference.
5. Worker writes result/update status in PostgreSQL.
6. Frontend polls job result.

## Messaging Contract

Queue name: `ml_jobs`

Message format:

```json
{
	"job_id": "...",
	"file_path": "...",
	"user_id": "..."
}
```

## Service Boundaries

### ml-service owns

- mzML parsing
- feature engineering from mz
- model training/inference
- worker loop/consumer logic

### ml-service does NOT own

- authentication
- backend REST business orchestration

### backend owns

- authentication (JWT)
- upload handling
- job lifecycle persistence
- publishing RabbitMQ jobs

### frontend owns

- upload and results UI
- auth UI

## Engineering Rules

- Keep solutions simple and working first.
- Keep modules decoupled and testable.
- Prefer explicit, reproducible pipelines over clever abstractions.
- Add small, meaningful tests for preprocessing + inference path.
- Document how to train and run worker locally.

## Git And Artifact Rules

- GitLab `origin` is the primary school repository. Local `main` tracks `origin/main` and should be used for normal school work.
- Push normal work to GitLab with:
  - `git checkout main`
  - `git pull origin main`
  - `git push origin main`
- GitHub `github` is a personal repository with separate cleaned history. Do not push local `main` to GitHub because GitLab history contains an old large model artifact commit that GitHub rejects.
- The local branch `github-main` maps to GitHub `main`. Push GitHub updates with:
  - `git checkout github-main`
  - `git push github github-main:main`
- Commits made on `main` do not automatically appear on `github-main`. To send a GitLab commit to GitHub, port it first, usually with:
  - `git checkout github-main`
  - `git cherry-pick <commit-sha-from-main>`
  - `git push github github-main:main`
- Do not assume `origin/main` and `github/main` can be cleanly mirrored unless GitLab history is rewritten by a maintainer/admin to remove the old large artifact commit.
- Generated ML artifacts must stay out of normal Git commits:
  - `ml-service/artifacts/*.joblib`
  - `ml-service/artifacts/*.pkl`
  - `ml-service/artifacts/*.pickle`
- Keep small metadata files such as `ml-service/artifacts/lipid_class_metadata.json` tracked when useful for reproducibility.
- If a model artifact must be shared, use Git LFS, a release asset, external storage, or regenerate it from the documented training command instead of committing the binary.
