# Kubernetes Deployment

These manifests deploy the Lipid Class Classifier application into the `lipid-classifier` namespace. PostgreSQL is deployed separately with the Bitnami Helm chart in replication mode so the database has a primary and one read replica.

## Prerequisites

- Kubernetes cluster with an Ingress controller, such as NGINX Ingress.
- Helm 3.
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

## Deploy PostgreSQL

Follow the PostgreSQL-specific instructions:

```bash
kubectl apply -f k8s/namespace.yaml
kubectl create secret generic lipid-postgres-secret \
  -n lipid-classifier \
  --from-literal=postgres-password=postgres \
  --from-literal=password=password \
  --from-literal=replication-password=replication-password

helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update
helm upgrade --install lipid-postgres bitnami/postgresql \
  -n lipid-classifier \
  -f k8s/postgres/values.yaml
```

For real deployments, use strong secret values instead of the local demo values above.

## Deploy Application Services

Apply the RabbitMQ, backend, worker, service, frontend, and ingress manifests:

```bash
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
```

The checked-in Secret manifests contain local demo credentials only.

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

Expected PostgreSQL pods after Helm install:

```text
lipid-postgres-postgresql-primary-0
lipid-postgres-postgresql-read-0
```

Smoke test the app through the browser:

1. Register or log in.
2. Upload an `.mzML` file.
3. Confirm the job moves from `PENDING` to `PROCESSING` to `DONE`.
4. Confirm predicted class and probability are displayed.

## Common Local Issues

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

The local demo manifest uses `ReadWriteOnce` for the uploads PVC because Rancher Desktop's default local path storage does not support `ReadWriteMany`.
