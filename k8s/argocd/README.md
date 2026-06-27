# Argo CD Deployment

These manifests add the optional CD bonus by letting Argo CD continuously deploy the Kubernetes application from the GitHub repository.

Argo CD manages a single application:

- `lipid-classifier`: deploys the whole app (a Kustomize overlay, including the manual PostgreSQL StatefulSets) from `https://github.com/andrejwastaken/lipid-class-classifier.git`, branch `main`, path `k8s`.

New image versions are rolled out automatically by the **GitHub Actions CI workflow**, which pins each freshly built image tag back into git — see [Continuous deployment of new image versions](#continuous-deployment-of-new-image-versions).

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

1. The GitHub Actions workflow (`.github/workflows/docker-publish.yml`) builds and pushes `…/<image>:<git-sha>` (and `:latest`) to DockerHub.
2. A second `update-manifests` job — which `needs` the build job, so it only runs after all three images are pushed — pins the new `<git-sha>` into the `newTag` values of `k8s/kustomization.yaml` (using `yq`).
3. It **commits the change back to `main`** using the workflow's `GITHUB_TOKEN` (`permissions: contents: write`). The commit message carries `[skip ci]`, and pushes made by `GITHUB_TOKEN` don't retrigger workflows anyway, so there's no build loop.
4. Argo CD sees the new commit and syncs, so the cluster runs the exact published version. Tags are immutable git SHAs, so rollbacks are a simple `git revert`.

No extra cluster-side controller or credentials are required — the git write-back
runs inside CI, where failures are visible directly in the workflow logs.

### Verify

```bash
# After a new push to main, confirm the bot tag-bump commit landed:
git log --oneline -- k8s/kustomization.yaml
# (look for a "ci: pin image tags to <sha> [skip ci]" commit)

# Then confirm Argo CD synced and the pods are running the new tag:
argocd app get lipid-classifier
kubectl get pods -n lipid-classifier
```

## Notes

The `lipid-classifier` Application points at the `k8s/` Kustomize overlay (`kustomization.yaml`), which lists the application manifests (including the manual PostgreSQL StatefulSets). The `k8s/argocd/` directory holds the Argo CD bootstrap manifests and is not part of the overlay, so Argo CD does not try to manage itself.

PostgreSQL is deployed from hand-written manifests (`k8s/postgres-deployment.yaml`) using physical streaming replication — no Bitnami/Helm chart. See `k8s/README.md` for the replication details and verification commands.
