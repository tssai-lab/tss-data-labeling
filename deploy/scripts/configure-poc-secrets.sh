#!/usr/bin/env bash
set -euo pipefail

namespace=tss-poc

if ! kubectl auth can-i create secrets -n "$namespace" >/dev/null; then
  echo "The current kubectl identity cannot create POC secrets." >&2
  exit 1
fi

read -r -p "GitHub username for GHCR: " ghcr_username
read -r -s -p "GitHub token with read:packages: " ghcr_token
echo

if [[ -z "$ghcr_username" || -z "$ghcr_token" ]]; then
  echo "Both GHCR values are required." >&2
  exit 1
fi

random_hex() {
  openssl rand -hex "$1"
}

postgres_password=$(random_hex 24)
minio_root_user="poc$(random_hex 6)"
minio_root_password=$(random_hex 24)
callback_token=$(random_hex 32)

kubectl -n "$namespace" create secret generic tss-poc-datastore \
  --from-literal=POSTGRES_PASSWORD="$postgres_password" \
  --from-literal=MINIO_ROOT_USER="$minio_root_user" \
  --from-literal=MINIO_ROOT_PASSWORD="$minio_root_password" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$namespace" create secret generic tss-backend-runtime \
  --from-literal=SPRING_PROFILES_ACTIVE=default \
  --from-literal=SPRING_DATASOURCE_URL=jdbc:postgresql://tss-postgres:5432/tss_poc \
  --from-literal=SPRING_DATASOURCE_USERNAME=postgres \
  --from-literal=SPRING_DATASOURCE_PASSWORD="$postgres_password" \
  --from-literal=MINIO_ENDPOINT=http://tss-minio:9000 \
  --from-literal=MINIO_ACCESS_KEY="$minio_root_user" \
  --from-literal=MINIO_SECRET_KEY="$minio_root_password" \
  --from-literal=MINIO_BUCKET=tss-poc \
  --from-literal=TRAINING_MLFLOW_ENABLED=false \
  --from-literal=TRAINING_KUBERNETES_ENABLED=false \
  --from-literal=TRAINING_KUBERNETES_VERIFY_ON_STARTUP=false \
  --from-literal=TRAINING_KUBERNETES_INTERNAL_CALLBACK_TOKEN="$callback_token" \
  --dry-run=client -o yaml | kubectl apply -f -

kubectl -n "$namespace" create secret docker-registry ghcr-pull \
  --docker-server=ghcr.io \
  --docker-username="$ghcr_username" \
  --docker-password="$ghcr_token" \
  --dry-run=client -o yaml | kubectl apply -f -

echo "POC datastore, runtime and registry secrets were created or updated."
