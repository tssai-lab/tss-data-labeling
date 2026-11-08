#!/usr/bin/env bash
# Test-only replacement for the withdrawn upstream image, built from its exact official release.
# No image is published and no production container or volume is touched.
set -Eeuo pipefail

readonly source_url=https://github.com/minio/minio.git
readonly source_revision=07c3a429bfed433e49018cb0f78a52145d4bedeb
readonly image=quay.io/minio/minio:RELEASE.2025-09-07T16-13-09Z

case "${1:-}" in
  checkout)
    : "${RUNNER_TEMP:?RUNNER_TEMP is required}"
    : "${GITHUB_OUTPUT:?GITHUB_OUTPUT is required}"
    source_dir="$(mktemp -d "${RUNNER_TEMP%/}/tss-minio-test.XXXXXX")"
    git -C "$source_dir" init --quiet
    git -C "$source_dir" remote add origin "$source_url"
    git -C "$source_dir" fetch --quiet --depth=1 origin "$source_revision"
    git -C "$source_dir" checkout --quiet --detach FETCH_HEAD
    [[ "$(git -C "$source_dir" rev-parse HEAD)" == "$source_revision" ]]
    printf 'source_dir=%s\n' "$source_dir" >> "$GITHUB_OUTPUT"
    ;;
  build)
    source_dir="${2:?Pass the checked-out source directory}"
    [[ "$(git -C "$source_dir" rev-parse HEAD)" == "$source_revision" ]]
    [[ "$(git -C "$source_dir" remote get-url origin)" == "$source_url" ]]
    [[ -z "$(git -C "$source_dir" status --porcelain)" ]]
    context="$(mktemp -d "${RUNNER_TEMP:?RUNNER_TEMP is required}/tss-minio-image.XXXXXX")"
    (
      cd "$source_dir"
      export CGO_ENABLED=0 GOOS=linux GOARCH=amd64 GOTOOLCHAIN=local
      flags="$(MINIO_RELEASE=RELEASE go run buildscripts/gen-ldflags.go '2025-09-07T16:13:09Z')"
      go build -mod=readonly -trimpath -ldflags "$flags" -o "$context/minio" .
    )
    cp "$source_dir/LICENSE" "$context/LICENSE"
    cp /etc/ssl/certs/ca-certificates.crt "$context/ca-certificates.crt"
    cat > "$context/Dockerfile" <<'DOCKERFILE'
FROM scratch
COPY minio /usr/bin/minio
COPY LICENSE /LICENSE
COPY ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
ENV HOME=/tmp
EXPOSE 9000 9001
ENTRYPOINT ["/usr/bin/minio"]
DOCKERFILE
    docker build --tag "$image" \
      --label "org.opencontainers.image.source=$source_url" \
      --label "org.opencontainers.image.revision=$source_revision" \
      --label 'com.tss.purpose=integration-test-only' "$context"
    docker run --rm "$image" --version | grep -F 'RELEASE.2025-09-07T16-13-09Z'
    # Fail before Maven if the fixture cannot start. Only remove the container we create here.
    container="$(docker run --rm -d -p 127.0.0.1::9000 \
      -e MINIO_ROOT_USER=ci-test-access -e MINIO_ROOT_PASSWORD=ci-test-secret-only \
      "$image" server /data --console-address :9001)"
    trap 'docker rm -f "$container" >/dev/null 2>&1 || true' EXIT
    port="$(docker inspect --format '{{(index (index .NetworkSettings.Ports "9000/tcp") 0).HostPort}}' "$container")"
    for attempt in {1..30}; do
      if curl --fail --silent --max-time 2 "http://127.0.0.1:$port/minio/health/ready" >/dev/null; then
        echo 'PASS: official-source MinIO test image is locally cached and healthy'
        exit 0
      fi
      sleep 1
    done
    docker logs "$container"
    echo 'MinIO test fixture did not become ready' >&2
    exit 1
    ;;
  *)
    echo 'Usage: prepare-minio-test-image.sh checkout | build SOURCE_DIR' >&2
    exit 2
    ;;
esac
