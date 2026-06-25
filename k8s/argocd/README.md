# Argo CD Deployment

These manifests add the optional CD bonus by letting Argo CD continuously deploy the Kubernetes application from the GitHub repository.

Argo CD manages a single application:

- `lipid-classifier`: deploys the whole app (a Kustomize overlay, including the manual PostgreSQL StatefulSets) from `https://github.com/andrejwastaken/lipid-class-classifier.git`, branch `main`, path `k8s`.

New image versions are rolled out automatically by **Argo CD Image Updater** — see [Continuous deployment of new image versions](#continuous-deployment-of-new-image-versions).

## Prerequisites

- Argo CD installed in the `argocd` namespace.
- GitHub `main` contains the Kubernetes manifests and Docker image tags to deploy.
- Docker images are already published to DockerHub.
- The trained model artifact is available on `lipid-model-artifacts-pvc`.

PostgreSQL credentials are committed as a manifest (`lipid-postgres-secret` in `postgres-deployment.yaml`) with local demo values, so no out-of-band secret creation is needed. Replace the demo values with strong secrets for a real environment.

## Install The Argo CD Application

Apply the project and application:

```bash
kubectl apply -f k8s/argocd/project.yaml
kubectl apply -f k8s/argocd/application.yaml
```

Argo CD will sync everything from this repository: the namespace, PostgreSQL primary plus read replica, RabbitMQ, backend, ML worker, frontend, services, PVCs, and ingress.

## Verify

```bash
argocd app get lipid-classifier

kubectl get pods -n lipid-classifier
kubectl get statefulsets -n lipid-classifier
kubectl get svc -n lipid-classifier
```

Expected PostgreSQL pods:

```text
lipid-postgres-primary-0
lipid-postgres-read-0
```

After the Argo CD application is healthy, verify the app flow through the ingress:

1. Register or log in.
2. Upload an `.mzML` file.
3. Confirm the job reaches `DONE`.
4. Confirm predicted class and probability are displayed.

## Continuous Deployment Of New Image Versions

This closes the CI/CD loop: a `git push` to `main` publishes new images and they
roll out to the cluster automatically, with no manual `kubectl` step.

How it works:

1. The GitHub Actions workflow builds and pushes `…/<image>:<git-sha>` (and `:latest`) to DockerHub.
2. [Argo CD Image Updater](https://argocd-image-updater.readthedocs.io/) (annotations on `application.yaml`) detects the newest `git-sha`-tagged build for each of the three app images.
3. It rewrites the `newTag` values in `k8s/kustomization.yaml` and **commits the change back to `main`** (`write-back-method: git`).
4. Argo CD sees the new commit and syncs, so the cluster runs the exact published version. Tags are immutable git SHAs, so rollbacks are a simple `git revert`.

The four cluster-side steps below (install Image Updater, create the git
write-back secret, create the DockerHub registry secret + config, restart Image
Updater) need a live cluster context and your credentials, so run them manually
after Argo CD is up.

### Install Image Updater

```bash
kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj-labs/argocd-image-updater/v1.2.2/config/install.yaml
```

### Git write-back credentials

Image Updater needs write access to push the tag bump. The Application annotation
references a secret named `git-creds` in the `argocd` namespace. Create it with a
GitHub Personal Access Token that has `repo`/`contents:write` scope:

```bash
kubectl create secret generic git-creds \
  -n argocd \
  --from-literal=username=<github-username> \
  --from-literal=password=<github-pat>
```

### DockerHub registry credentials (recommended)

To avoid anonymous DockerHub rate limits while polling tags, give Image Updater
registry credentials (anonymous works for public images but may be throttled):

```bash
kubectl create secret docker-registry dockerhub-creds \
  -n argocd \
  --docker-server=https://registry-1.docker.io \
  --docker-username=<dockerhub-username> \
  --docker-password=<dockerhub-token>
```

Then apply the committed `argocd-image-updater-config` ConfigMap (which points the
`docker.io` registry at that pull secret) and restart Image Updater:

```bash
kubectl apply -f k8s/argocd/image-updater-config.yaml
kubectl rollout restart deployment/argocd-image-updater-controller -n argocd
```

### Verify

```bash
kubectl -n argocd logs deploy/argocd-image-updater-controller -f
# After a new push, confirm the tag bump commit lands and the pods update:
git log --oneline -- k8s/kustomization.yaml
kubectl get pods -n lipid-classifier
```

## Notes

The `lipid-classifier` Application points at the `k8s/` Kustomize overlay (`kustomization.yaml`), which lists the application manifests (including the manual PostgreSQL StatefulSets). The `k8s/argocd/` directory holds Argo CD/Image Updater bootstrap manifests and is not part of the overlay, so Argo CD does not try to manage itself.

PostgreSQL is deployed from hand-written manifests (`k8s/postgres-deployment.yaml`) using physical streaming replication — no Bitnami/Helm chart. See `k8s/README.md` for the replication details and verification commands.
