#!/usr/bin/env bash
# Stand up the WHOLE bank on a real Kubernetes cluster, from nothing, with one
# command. Uses k3d (real k3s in Docker) so it costs nothing and runs on a
# laptop · the same manifests would apply to EKS/GKE unchanged.
#
#   ./k8s/run-local.sh
#
# Then:
#   kubectl get pods -o wide
#   kubectl port-forward svc/grafana 3000:3000     # http://localhost:3000  (admin/admin)
#   kubectl port-forward svc/minibank 8080:80      # http://localhost:8080
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=minibank

echo "==> creating k3d cluster (1 server + 2 agents)"
k3d cluster delete "$CLUSTER" >/dev/null 2>&1 || true
k3d cluster create "$CLUSTER" --servers 1 --agents 2 --wait

echo "==> labelling agent nodes with regions (data residency = a scheduling constraint)"
kubectl label node k3d-${CLUSTER}-agent-0 topology.kubernetes.io/region=eu-west-1 --overwrite
kubectl label node k3d-${CLUSTER}-agent-1 topology.kubernetes.io/region=eu-west-2 --overwrite

echo "==> building and importing the image"
docker build -t minibank:local ledger-service
k3d image import minibank:local -c "$CLUSTER"

echo "==> applying manifests"
kubectl apply -f k8s/secret.yaml \
              -f k8s/regions.yaml \
              -f k8s/kafka.yaml \
              -f k8s/redis.yaml \
              -f k8s/fx.yaml \
              -f k8s/app.yaml \
              -f k8s/monitoring.yaml

echo "==> waiting for the app to come up"
kubectl rollout status deployment/minibank-app --timeout=180s

echo
echo "cluster is up:"
kubectl get pods -o wide
echo
echo "pg-eu is pinned to eu-west-1, pg-uk to eu-west-2 · residency enforced by the scheduler."
echo "grafana:  kubectl port-forward svc/grafana 3000:3000   -> http://localhost:3000"
echo "the app:  kubectl port-forward svc/minibank 8080:80    -> http://localhost:8080"
