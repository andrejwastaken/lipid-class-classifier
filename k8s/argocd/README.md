# Argo CD Deployment

These manifests add the optional CD bonus by letting Argo CD continuously deploy the Kubernetes application from the GitHub repository.

Argo CD manages two applications:

- `lipid-postgres`: deploys the Bitnami PostgreSQL Helm chart in replication mode.
- `lipid-classifier`: deploys the app manifests from `https://github.com/andrejwastaken/lipid-class-classifier.git`, branch `main`, path `k8s`.

## Prerequisites

- Argo CD installed in the `argocd` namespace.
- GitHub `main` contains the Kubernetes manifests and Docker image tags to deploy.
- Docker images are already published to DockerHub.
- PostgreSQL credentials are created before syncing the PostgreSQL application.
- The trained model artifact is available on `lipid-model-artifacts-pvc`.

Create the namespace and PostgreSQL secret first:

```bash
kubectl apply -f k8s/namespace.yaml

kubectl create secret generic lipid-postgres-secret \
  -n lipid-classifier \
  --from-literal=postgres-password=postgres \
  --from-literal=password=password \
  --from-literal=replication-password=replication-password
```

For a real environment, replace these demo values with strong secrets.

## Install The Argo CD Applications

Apply the project and applications:

```bash
kubectl apply -f k8s/argocd/project.yaml
kubectl apply -f k8s/argocd/postgres-application.yaml
kubectl apply -f k8s/argocd/application.yaml
```

Argo CD will sync:

1. PostgreSQL primary plus one read replica from the Bitnami chart.
2. RabbitMQ, backend, ML worker, frontend, services, PVCs, and ingress from this repository.

## Verify

```bash
argocd app get lipid-postgres
argocd app get lipid-classifier

kubectl get pods -n lipid-classifier
kubectl get statefulsets -n lipid-classifier
kubectl get svc -n lipid-classifier
```

Expected PostgreSQL pods:

```text
lipid-postgres-postgresql-primary-0
lipid-postgres-postgresql-read-0
```

After both Argo CD applications are healthy, verify the app flow through the ingress:

1. Register or log in.
2. Upload an `.mzML` file.
3. Confirm the job reaches `DONE`.
4. Confirm predicted class and probability are displayed.

## Notes

The PostgreSQL Argo CD application uses inline Helm values matching `k8s/postgres/values.yaml`. If you change PostgreSQL settings, update both files or switch the Argo CD app to a multi-source Application that reads the values file from Git.

The app Application uses `directory.recurse: false`, so Argo CD applies only the top-level manifests in `k8s/` and does not recursively apply the `k8s/argocd/` helper manifests.
