#!/usr/bin/env bash
set -Eeuo pipefail

cat <<'EOF'
apiVersion: node.k8s.io/v1
kind: RuntimeClass
metadata:
  name: nvidia
handler: nvidia
EOF
