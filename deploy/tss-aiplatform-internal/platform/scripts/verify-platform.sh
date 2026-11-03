#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib-platform.sh
source "${script_dir}/lib-platform.sh"

fresh=false
if [[ ${1:-} == --fresh ]]; then
  fresh=true
  shift
fi
node_config="${1:-}"
platform_config="${2:-}"
secrets_file="${3:-}"
load_platform_config "$node_config" "$platform_config"
load_platform_secrets "$secrets_file"
[[ $(id -u) -eq 0 ]] || die "platform verification must run as root"
require_control_plane_identity
require_space_gates
"${script_dir}/verify-platform-images.sh" "$node_config" "$platform_config" >/dev/null

containers=(
  tss-aiplatform-internal-postgres
  tss-aiplatform-internal-redis
  tss-aiplatform-internal-minio
  tss-aiplatform-internal-mlflow
  tss-aiplatform-internal-backend
)
for container in "${containers[@]}"; do
  [[ $(docker inspect -f '{{.State.Running}}' "$container" 2>/dev/null || true) == true ]] \
    || die "platform container is not running: $container"
done

backend_url="http://${TSS_PLATFORM_BIND_IP}:${TSS_BACKEND_PORT}"
ready_file="$(mktemp)"
callback_config="$(mktemp)"
callback_body="$(mktemp)"
trap 'rm -f "$ready_file" "$callback_config" "$callback_body"' EXIT
ready=false
for _attempt in $(seq 1 60); do
  if curl --silent --show-error --fail --max-time 5 "$backend_url/health/ready" >"$ready_file" 2>/dev/null; then
    ready=true
    break
  fi
  sleep 3
done
[[ $ready == true ]] || die "backend readiness did not become available within 180 seconds"
python3 - "$ready_file" <<'PY'
import json, sys
payload = json.load(open(sys.argv[1], encoding="utf-8"))
if payload.get("status") != "UP":
    raise SystemExit("overall readiness is not UP")
components = payload.get("components") or {}
for name in ("database", "objectStorage", "training", "authSession"):
    component = components.get(name)
    status = component.get("status") if isinstance(component, dict) else component
    if status != "UP":
        raise SystemExit(f"readiness component is not UP: {name}")
PY
[[ $(docker exec tss-aiplatform-internal-redis redis-cli ping) == PONG ]] \
  || die "project Redis session store did not return PONG"
[[ $(docker inspect -f '{{.Config.Image}}' tss-aiplatform-internal-redis) == "$TSS_REDIS_IMAGE" ]] \
  || die "running Redis image differs from the reviewed platform alias"
[[ $(docker port tss-aiplatform-internal-redis 6379/tcp) == "127.0.0.1:${TSS_REDIS_PORT}" ]] \
  || die "Redis session store is not bound only to the reviewed loopback port"
docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' \
  tss-aiplatform-internal-backend | grep -Fx 'AUTH_SESSION_STORE=redis' >/dev/null \
  || die "backend is not configured for Redis-backed sessions"
curl --silent --show-error --fail --max-time 5 \
  "http://${TSS_PLATFORM_BIND_IP}:${TSS_MINIO_API_PORT}/minio/health/live" >/dev/null
curl --silent --show-error --fail --max-time 5 \
  "http://${TSS_PLATFORM_BIND_IP}:${TSS_MLFLOW_PORT}/" \
  | grep -F '"message": "ok"' >/dev/null
files_health_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --max-time 5 "$backend_url/api/files/health")"
[[ $files_health_status == 401 ]] \
  || die "protected file-service health endpoint did not reject an anonymous request"

roles="$(docker exec tss-aiplatform-internal-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from roles"')"
flyway="$(docker exec tss-aiplatform-internal-postgres sh -c \
  'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select coalesce(max(version::integer),0) from flyway_schema_history where success and version ~ '\''^[0-9]+$'\''"')"
repo_root="$(cd "${script_dir}/../../../.." && pwd)"
expected_flyway=0
shopt -s nullglob
for migration in "${repo_root}/backend/src/main/resources/db/migration"/V[0-9]*__*.sql; do
  migration_version="${migration##*/V}"
  migration_version="${migration_version%%__*}"
  [[ $migration_version =~ ^[0-9]+$ ]] || continue
  if (( migration_version > expected_flyway )); then
    expected_flyway="$migration_version"
  fi
done
shopt -u nullglob
(( expected_flyway > 0 )) || die "no versioned Flyway migrations were found in the deployed source"
[[ $roles == 3 ]] || die "module-one role initialization is incomplete"
[[ $flyway == "$expected_flyway" ]] \
  || die "Flyway schema is not at the expected application baseline: actual=$flyway expected=$expected_flyway"
if [[ $fresh == true ]]; then
  users="$(docker exec tss-aiplatform-internal-postgres sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select count(*) from users"')"
  assets="$(docker exec tss-aiplatform-internal-postgres sh -c \
    'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "select (select count(*) from model_asset)+(select count(*) from dataset_asset)+(select count(*) from training_experiment_version)"')"
  [[ $users == 0 && $assets == 0 ]] \
    || die "fresh internal platform unexpectedly contains user or business data"
fi

KUBECTL="$TSS_KUBECTL_PATH" KUBECONFIG="${TSS_PLATFORM_ROOT}/config/backend.kubeconfig" \
  "${script_dir}/verify-internal-kubeadm.sh" >/dev/null

invalid_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --max-time 5 -X POST -H 'Content-Type: application/json' \
  --data '{"status":"failed","progress":0,"errorMessage":"c5-auth-probe"}' \
  "$backend_url/api/internal/training/result?id=c5-nonexistent")"
[[ $invalid_status == 401 ]] || die "internal callback without a token was not rejected"
{
  printf 'header = "Content-Type: application/json"\n'
  printf 'header = "X-Internal-Token: %s"\n' "$TSS_INTERNAL_CALLBACK_TOKEN"
  printf 'request = "POST"\n'
  printf 'data = "{\\"status\\":\\"failed\\",\\"progress\\":0,\\"errorMessage\\":\\"c5-auth-probe\\"}"\n'
  printf 'silent\nshow-error\nmax-time = 5\n'
} >"$callback_config"
chmod 0600 "$callback_config"
valid_status="$(curl --config "$callback_config" --output "$callback_body" --write-out '%{http_code}' \
  "$backend_url/api/internal/training/result?id=c5-nonexistent")"
[[ $valid_status == 200 ]] || die "valid internal callback credential did not reach the controller"

[[ -d ${TSS_PLATFORM_ROOT}/logs/backend && -d ${TSS_PLATFORM_ROOT}/data/mlflow ]] \
  || die "persistent logs or MLflow data directory is absent"
for secret in "$TSS_POSTGRES_PASSWORD" "$TSS_MINIO_ROOT_PASSWORD" "$TSS_INTERNAL_CALLBACK_TOKEN"; do
  if grep -R -F -- "$secret" "${TSS_PLATFORM_ROOT}/logs/backend" >/dev/null 2>&1; then
    die "a generated secret appeared in backend logs"
  fi
done
echo "PASS: database, object storage, MLflow, restricted Kubernetes access and callback authentication are healthy"
