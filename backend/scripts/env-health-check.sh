#!/usr/bin/env bash
# S0-ENV-01: Unified environment health check for TSS Platform.
# Checks PostgreSQL, MinIO, MLflow, Backend, and kind — without printing credentials.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
KUBECTL="${KUBECTL:-${ROOT_DIR}/.tools/bin/kubectl}"
KUBECONFIG_PATH="${KUBECONFIG_PATH:-${ROOT_DIR}/k8s/.kube/config}"
CLUSTER_NAME="${CLUSTER_NAME:-tss-training}"

PG_HOST="${PG_HOST:-127.0.0.1}"
PG_PORT="${PG_PORT:-5432}"
PG_CONTAINER="${PG_CONTAINER:-tss-postgres}"

MINIO_HOST="${MINIO_HOST:-127.0.0.1}"
MINIO_PORT="${MINIO_PORT:-9010}"

MLFLOW_HOST="${MLFLOW_HOST:-127.0.0.1}"
MLFLOW_PORT="${MLFLOW_PORT:-5000}"

BACKEND_HOST="${BACKEND_HOST:-127.0.0.1}"
BACKEND_PORT="${BACKEND_PORT:-8080}"

WITH_POD_CONNECTIVITY=false
SKIP_KIND=false

usage() {
  cat <<'EOF'
Usage: env-health-check.sh [OPTIONS]

Unified health check for PostgreSQL, MinIO, MLflow, Backend, and kind.
Does not print credential values.

Options:
  --with-pod-connectivity  Also run k8s connectivity Job (slower, ~1-3 min)
  --skip-kind              Skip kind / kubectl checks
  -h, --help               Show this help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --with-pod-connectivity)
      WITH_POD_CONNECTIVITY=true
      shift
      ;;
    --skip-kind)
      SKIP_KIND=true
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

PASS_COUNT=0
FAIL_COUNT=0
TOTAL_COUNT=0

record_result() {
  local status="$1"
  local name="$2"
  local detail="$3"
  TOTAL_COUNT=$((TOTAL_COUNT + 1))
  if [[ "${status}" == "pass" ]]; then
    PASS_COUNT=$((PASS_COUNT + 1))
    printf '[PASS] %-10s %s\n' "${name}" "${detail}"
  else
    FAIL_COUNT=$((FAIL_COUNT + 1))
    printf '[FAIL] %-10s %s\n' "${name}" "${detail}"
  fi
}

http_code() {
  local url="$1"
  local code=""
  code="$(curl -sS -o /dev/null -w '%{http_code}' --connect-timeout 5 --max-time 10 "${url}" 2>/dev/null || true)"
  if [[ "${code}" =~ ^[0-9]{3}$ ]]; then
    echo "${code}"
  else
    echo "000"
  fi
}

check_postgresql() {
  local detail=""
  if docker ps --format '{{.Names}}' 2>/dev/null | grep -Fxq "${PG_CONTAINER}"; then
    if docker exec "${PG_CONTAINER}" pg_isready -U postgres -h localhost -p 5432 >/dev/null 2>&1; then
      record_result pass "PostgreSQL" "${PG_HOST}:${PG_PORT}  accepting connections (container ${PG_CONTAINER})"
      return
    fi
    detail="${PG_HOST}:${PG_PORT}  container running but pg_isready failed"
  elif command -v nc >/dev/null 2>&1 && nc -z "${PG_HOST}" "${PG_PORT}" 2>/dev/null; then
    record_result pass "PostgreSQL" "${PG_HOST}:${PG_PORT}  TCP port open (container ${PG_CONTAINER} not found)"
    return
  elif command -v nc >/dev/null 2>&1; then
    detail="${PG_HOST}:${PG_PORT}  port closed; container ${PG_CONTAINER} not running"
  else
    detail="${PG_HOST}:${PG_PORT}  unreachable; install nc or start ${PG_CONTAINER}"
  fi
  record_result fail "PostgreSQL" "${detail}"
}

check_minio() {
  local code
  code="$(http_code "http://${MINIO_HOST}:${MINIO_PORT}/minio/health/live")"
  if [[ "${code}" == "200" ]]; then
    record_result pass "MinIO" "${MINIO_HOST}:${MINIO_PORT}  /minio/health/live → 200"
  else
    record_result fail "MinIO" "${MINIO_HOST}:${MINIO_PORT}  /minio/health/live → ${code}"
  fi
}

check_mlflow() {
  local code
  code="$(http_code "http://${MLFLOW_HOST}:${MLFLOW_PORT}/")"
  if [[ "${code}" == "200" ]]; then
    record_result pass "MLflow" "${MLFLOW_HOST}:${MLFLOW_PORT}  HTTP → 200"
  else
    record_result fail "MLflow" "${MLFLOW_HOST}:${MLFLOW_PORT}  HTTP → ${code}"
  fi
}

check_backend() {
  local code systemd_state=""
  code="$(http_code "http://${BACKEND_HOST}:${BACKEND_PORT}/api/user/current-user")"

  if command -v systemctl >/dev/null 2>&1; then
    systemd_state="$(systemctl is-active tss-backend.service 2>/dev/null || true)"
  fi

  if [[ "${code}" == "401" ]]; then
    local extra=""
    if [[ -n "${systemd_state}" ]]; then
      extra=", systemd=${systemd_state}"
    fi
    record_result pass "Backend" "${BACKEND_HOST}:${BACKEND_PORT}  /api/user/current-user → 401 (service up${extra})"
  elif [[ "${code}" == "200" ]]; then
    record_result pass "Backend" "${BACKEND_HOST}:${BACKEND_PORT}  /api/user/current-user → 200 (service up)"
  elif [[ "${code}" != "000" ]]; then
    record_result pass "Backend" "${BACKEND_HOST}:${BACKEND_PORT}  HTTP → ${code} (port responding)"
  else
    local extra=""
    if [[ -n "${systemd_state}" ]]; then
      extra="; systemd=${systemd_state}"
    fi
    record_result fail "Backend" "${BACKEND_HOST}:${BACKEND_PORT}  not reachable${extra}"
  fi
}

check_kind() {
  if [[ ! -x "${KUBECTL}" ]]; then
    record_result fail "kind" "kubectl not found at ${KUBECTL}"
    return
  fi
  if [[ ! -f "${KUBECONFIG_PATH}" ]]; then
    record_result fail "kind" "kubeconfig missing: ${KUBECONFIG_PATH}"
    return
  fi

  export KUBECONFIG="${KUBECONFIG_PATH}"
  local nodes_output ready_count total_count
  if ! nodes_output="$("${KUBECTL}" get nodes --no-headers 2>/dev/null)"; then
    record_result fail "kind" "cluster=${CLUSTER_NAME}  kubectl cannot reach API (${KUBECONFIG_PATH})"
    return
  fi

  if [[ -z "${nodes_output}" ]]; then
    record_result fail "kind" "cluster=${CLUSTER_NAME}  no nodes registered"
    return
  fi

  ready_count="$(echo "${nodes_output}" | awk '$2 == "Ready" { c++ } END { print c + 0 }')"
  total_count="$(echo "${nodes_output}" | wc -l | tr -d ' ')"

  if [[ "${ready_count}" -eq "${total_count}" && "${total_count}" -gt 0 ]]; then
    local ns_ok=false svc_count=0
    if "${KUBECTL}" get namespace tss-training >/dev/null 2>&1; then
      ns_ok=true
      svc_count="$("${KUBECTL}" get svc -n tss-training --no-headers 2>/dev/null | wc -l | tr -d ' ')"
    fi
    if [[ "${ns_ok}" == "true" && "${svc_count}" -ge 3 ]]; then
      record_result pass "kind" "cluster=${CLUSTER_NAME}  ${ready_count}/${total_count} node(s) Ready; ns=tss-training svc=${svc_count}"
    elif [[ "${ns_ok}" == "true" ]]; then
      record_result fail "kind" "cluster=${CLUSTER_NAME}  nodes Ready but tss-training services incomplete (found ${svc_count}, need ≥3)"
    else
      record_result fail "kind" "cluster=${CLUSTER_NAME}  nodes Ready but namespace tss-training missing; run bootstrap-local-kind.sh"
    fi
  else
    record_result fail "kind" "cluster=${CLUSTER_NAME}  ${ready_count}/${total_count} node(s) Ready"
  fi
}

check_pod_connectivity() {
  local verify_script="${ROOT_DIR}/backend/scripts/k8s/verify-local-kind.sh"
  if [[ ! -x "${verify_script}" ]]; then
    record_result fail "k8s-pod" "verify script missing: ${verify_script}"
    return
  fi
  if "${verify_script}" >/dev/null 2>&1; then
    record_result pass "k8s-pod" "connectivity Job complete (Pod → Backend/MinIO/MLflow)"
  else
    record_result fail "k8s-pod" "connectivity Job failed; run: ${verify_script}"
  fi
}

main() {
  echo "TSS Platform environment health check"
  echo "Root: ${ROOT_DIR}"
  echo "---"

  check_postgresql
  check_minio
  check_mlflow
  check_backend

  if [[ "${SKIP_KIND}" == "false" ]]; then
    check_kind
  fi

  if [[ "${WITH_POD_CONNECTIVITY}" == "true" ]]; then
    check_pod_connectivity
  fi

  echo "---"
  echo "Result: ${PASS_COUNT}/${TOTAL_COUNT} passed"

  if [[ "${FAIL_COUNT}" -gt 0 ]]; then
    exit 1
  fi
}

main "$@"
