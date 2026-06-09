# PostgreSQL On Kubernetes

The Kubernetes deployment uses the Bitnami PostgreSQL Helm chart in replication mode. This creates one read/write primary and one read replica. Do not replace this with a plain `replicas: 2` StatefulSet because PostgreSQL replication must be configured explicitly.

## Install

Create the namespace and database secret:

```bash
kubectl apply -f k8s/namespace.yaml

kubectl create secret generic lipid-postgres-secret \
  -n lipid-classifier \
  --from-literal=postgres-password=postgres \
  --from-literal=password=password \
  --from-literal=replication-password=replication-password
```

Install PostgreSQL:

```bash
helm repo add bitnami https://charts.bitnami.com/bitnami
helm repo update

helm upgrade --install lipid-postgres bitnami/postgresql \
  -n lipid-classifier \
  -f k8s/postgres/values.yaml
```

The backend writes to the primary service:

```text
lipid-postgres-postgresql-primary.lipid-classifier.svc.cluster.local:5432
```

If read-only database traffic is added later, route it to the read service:

```text
lipid-postgres-postgresql-read.lipid-classifier.svc.cluster.local:5432
```

## Verify

```bash
kubectl get pods -n lipid-classifier
kubectl get statefulsets -n lipid-classifier
kubectl get svc -n lipid-classifier
```

Expected PostgreSQL pods:

```text
lipid-postgres-postgresql-primary-0
lipid-postgres-postgresql-read-0
```

After the application manifests are applied, verify that the backend can connect to the primary database and that a registered user can upload an `.mzML` file, creating and reading a job successfully.

