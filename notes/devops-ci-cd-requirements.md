# DevOps CI/CD Requirements

This note captures the DevOps assignment requirements tied to the Lipid Class Classifier app. It is planning/documentation only; do not implement Docker, CI/CD, or Kubernetes changes unless explicitly requested.

## Assignment Context

Select a ready application that has at least three services, including a database. For this repository, the natural service set is:

- frontend
- backend
- ml-service / worker
- PostgreSQL database
- RabbitMQ, if the message queue is included in the demonstrated architecture

The app can be an existing project from another course or an open-source app found online. In this repository, use the Lipid Class Classifier as the DevOps target unless the user says otherwise.

## Grading Breakdown

- 10%: Put the application on a public git repository.
- 10%: Dockerize the application.
- 10%: Orchestrate the application and database with Docker Compose.
- 20%: Choose one CI platform, such as GitHub Actions, GitLab CI, or Jenkins, and set up a full CI or CI/CD pipeline.
- The CI/CD pipeline must publish a new Docker image version to an appropriate registry, for example DockerHub, on git push.
- Optional bonus: include a CD stage that deploys the application to a deployment environment, such as a server or cloud instance, using Docker orchestration, Kubernetes, Argo CD, or a similar tool.

## Kubernetes Manifest Requirements

- 10%: Deployment for the application with required ConfigMaps and Secrets.
- 10%: Service for the application.
- 10%: Ingress for the application.
- 10%: StatefulSet for the database with required ConfigMaps and Secrets.
- 10%: Apply the manifests in a separate namespace on the user's cluster and demonstrate that the app works.

## Implementation Assumptions For Later

- Keep Kubernetes manifests under a dedicated directory, for example `k8s/`, when implementation is requested.
- Use a separate namespace for the DevOps demo, not the default namespace.
- Keep secrets out of git; commit templates or documented environment variable names instead.
- Prefer one registry naming convention for all images.
- The CI platform should match the public repository target unless the user chooses another platform.
- If using GitHub for the DevOps submission, remember this repository has special GitLab/GitHub branch rules in `AGENTS.MD`.
