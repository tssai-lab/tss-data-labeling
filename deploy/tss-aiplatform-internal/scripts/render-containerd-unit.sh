#!/usr/bin/env bash
set -Eeuo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib.sh
source "${script_dir}/lib.sh"
load_internal_config "${1:-}"

cat <<EOF
[Unit]
Description=TSS AIPlatform isolated containerd
Documentation=https://github.com/tssai-lab/TSSAIPlatform
After=network-online.target local-fs.target
Wants=network-online.target
RequiresMountsFor=${TSS_STORAGE_MOUNT_POINT}

[Service]
Type=notify
EnvironmentFile=/etc/tss-aiplatform/node.env
ExecStartPre=/bin/bash /usr/local/lib/tss-aiplatform-internal/scripts/verify-storage.sh /etc/tss-aiplatform/node.env
ExecStartPre=/usr/bin/install -d -m 0755 ${TSS_CONTAINERD_STATE_DIR}
ExecStart=/usr/bin/containerd --config /etc/tss-aiplatform/containerd.toml
Restart=always
RestartSec=5
Delegate=yes
KillMode=process
OOMScoreAdjust=-500
LimitNOFILE=1048576

[Install]
WantedBy=multi-user.target
EOF
