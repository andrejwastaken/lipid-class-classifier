# Kubernetes Deployment

These manifests deploy the Lipid Class Classifier application into the `lipid-classifier` namespace. PostgreSQL runs as hand-written StatefulSets (`postgres-deployment.yaml`) using physical streaming replication, with one read/write primary and one read-only replica. Everything, including the database, is part of the `k8s/` Kustomize overlay.

For a visual overview of every object and how they connect, see [`ARCHITECTURE.md`](ARCHITECTURE.md).

## Prerequisites

- Kubernetes cluster with an Ingress controller, such as NGINX Ingress.
- Docker images published by the GitHub CI workflow.
- Trained model artifact available outside Git: `lipid_class_pipeline.joblib`.
- Storage class for PVCs. The local demo manifest uses `ReadWriteOnce`, which works on a single-node local cluster where backend and worker are scheduled onto the same node. For a multi-node cluster, change `lipid-uploads-pvc` to `ReadWriteMany` and use a storage class that supports RWX.

Before applying the app manifests, replace the image placeholders in these files with your DockerHub namespace:

```text
k8s/backend-deployment.yaml
k8s/frontend-deployment.yaml
k8s/ml-worker-deployment.yaml
```

Example:

```text
docker.io/your-dockerhub-username/lipid-classifier-backend:latest
```

On Apple Silicon or other ARM machines, Kubernetes needs `linux/arm64` images. The GitHub workflow publishes multi-architecture images for `linux/amd64` and `linux/arm64`; after changing the workflow, push to the GitHub `main` branch and wait for the Docker publish workflow to finish before restarting the Kubernetes deployments.

## PostgreSQL Replication

PostgreSQL is defined manually in `postgres-deployment.yaml`:

- `lipid-postgres-primary` StatefulSet — the read/write primary. On first start it creates a dedicated `replicator` role and opens `pg_hba.conf` for replication connections.
- `lipid-postgres-read` StatefulSet — a hot-standby replica. Its init container clones the primary with `pg_basebackup` and starts in standby mode using physical streaming replication.
- `lipid-postgres-secret` — manually managed credentials (the app password and the replication password). The checked-in values are local demo values; replace them before production.

Services:

- `lipid-postgres-primary` — read/write endpoint used by the backend and ML worker.
- `lipid-postgres-read` — read-only endpoint for replica traffic (the application does not need to change to use it).

The database is deployed together with the rest of the app via the Kustomize overlay below; no Helm is required.

## Deploy Application Services

`k8s/` is a Kustomize overlay (`kustomization.yaml`) covering the namespace, PostgreSQL, RabbitMQ, backend, ML worker, frontend, services, and ingress, so the whole app deploys in one command:

```bash
kubectl apply -k k8s
```

Or apply the individual manifests:

```bash
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/postgres-deployment.yaml
kubectl apply -f k8s/rabbitmq-deployment.yaml
kubectl apply -f k8s/backend-deployment.yaml
kubectl apply -f k8s/ml-worker-deployment.yaml
kubectl apply -f k8s/frontend-deployment.yaml
kubectl apply -f k8s/services.yaml
kubectl apply -f k8s/ingress.yaml
```

Update the app secrets before production use:

```bash
kubectl edit secret lipid-backend-secret -n lipid-classifier
kubectl edit secret lipid-ml-worker-secret -n lipid-classifier
kubectl edit secret lipid-rabbitmq-secret -n lipid-classifier
kubectl edit secret lipid-postgres-secret -n lipid-classifier
```

The checked-in Secret manifests contain local demo credentials only.

## Optional Argo CD Deployment

The optional CD bonus is documented in:

```text
k8s/argocd/README.md
```

Argo CD uses a single application, `lipid-classifier`, which deploys this repository's `k8s/` Kustomize overlay (including the manual PostgreSQL StatefulSets) from GitHub `main`. Argo CD Image Updater watches DockerHub and auto-rolls out each new pushed build by committing the new image tag back to `k8s/kustomization.yaml` — see `k8s/argocd/README.md`.

Install after Argo CD is running in the cluster:

```bash
kubectl apply -f k8s/argocd/project.yaml
kubectl apply -f k8s/argocd/application.yaml
```

## Model Artifact

The worker expects the trained model at:

```text
/app/artifacts/lipid_class_pipeline.joblib
```

The manifest mounts `lipid-model-artifacts-pvc` at `/app/artifacts`. Populate that PVC with the generated model artifact before expecting jobs to complete. One simple local-cluster approach is to start a temporary pod that mounts the PVC, copy the artifact into it with `kubectl cp`, then delete the pod.

The model artifact remains out of Git by design.

## Routing

Ingress host:

```text
lipid-classifier.local
```

For local clusters, map the ingress IP to that hostname in `/etc/hosts`, then open:

```text
http://lipid-classifier.local
```

The frontend Nginx image proxies `/api/*` to the Kubernetes service named `backend`.

## Verify

```bash
kubectl get pods -n lipid-classifier
kubectl get deploy -n lipid-classifier
kubectl get statefulsets -n lipid-classifier
kubectl get pvc -n lipid-classifier
kubectl get svc -n lipid-classifier
kubectl get ingress -n lipid-classifier
```

Expected PostgreSQL pods:

```text
lipid-postgres-primary-0
lipid-postgres-read-0
```

Confirm replication is streaming (run against the primary pod):

```bash
kubectl exec -n lipid-classifier lipid-postgres-primary-0 -- \
  psql -U user -d app_db -c "SELECT client_addr, state, sync_state FROM pg_stat_replication;"
```

One row in `streaming` state means the replica is connected. You can also confirm the replica is read-only:

```bash
kubectl exec -n lipid-classifier lipid-postgres-read-0 -- \
  psql -U user -d app_db -c "SELECT pg_is_in_recovery();"   # expect: t
```

Smoke test the app through the browser:

1. Register or log in.
2. Upload an `.mzML` file.
3. Confirm the job moves from `PENDING` to `PROCESSING` to `DONE`.
4. Confirm predicted class and probability are displayed.

## Common Local Issues

If uploads fail through Kubernetes with `413 Request Entity Too Large`, re-apply the ingress manifest so NGINX Ingress picks up the upload body-size limit:

```bash
kubectl apply -f k8s/ingress.yaml
kubectl describe ingress lipid-classifier -n lipid-classifier
```

The ingress should include:

```text
nginx.ingress.kubernetes.io/proxy-body-size: 100m
```

If pods show `ImagePullBackOff` and `docker pull` says there is no `linux/arm64` manifest, the DockerHub image was built only for `linux/amd64`. Push the current GitHub workflow update, wait for the image publish job to complete, then restart the deployments:

```bash
kubectl rollout restart deployment/lipid-backend -n lipid-classifier
kubectl rollout restart deployment/lipid-frontend -n lipid-classifier
kubectl rollout restart deployment/lipid-ml-worker -n lipid-classifier
```

If `lipid-uploads-pvc` stays `Pending`, check the event message:

```bash
kubectl get events -n lipid-classifier --sort-by=.lastTimestamp
```

The local demo manifest uses `ReadWriteOnce` for the uploads PVC because Docker Desktop's default local path storage does not support `ReadWriteMany`.
