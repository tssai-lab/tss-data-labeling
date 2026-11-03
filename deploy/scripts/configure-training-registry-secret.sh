#!/usr/bin/env bash
# 为训练 worker 配置阿里云私有镜像仓库拉取凭证（imagePullSecret）。
#
# 背景：tss-cv-worker / tss-nlp-worker 位于阿里云私有仓库，节点（尤其新节点）没有凭证，
# kubelet 在 `imagePullPolicy: IfNotPresent` 下拉不动。本脚本：
#   1) 在命名空间创建 docker-registry secret；
#   2) 把该 secret 挂到训练 worker 的 ServiceAccount（tss-training-worker）上，
#      这样所有用该 SA 生成的训练 pod 都能自动带凭证拉私有镜像，无需改后端代码。
#
# 幂等：可重复执行。
set -euo pipefail

namespace="${TSS_K8S_NAMESPACE:-tss-training}"
service_account="${TSS_TRAINING_SERVICE_ACCOUNT:-tss-training-worker}"
secret_name="${TSS_ACR_SECRET_NAME:-aliyun-registry}"
server="${TSS_ACR_SERVER:-crpi-s1uie3z8n3mbqf6y.cn-shanghai.personal.cr.aliyuncs.com}"

if ! kubectl auth can-i create secrets -n "$namespace" >/dev/null 2>&1; then
  echo "The current kubectl identity cannot create secrets in namespace $namespace." >&2
  exit 1
fi

if [[ -z "${TSS_ACR_USERNAME:-}" ]]; then
  read -r -p "Aliyun ACR username: " TSS_ACR_USERNAME
  export TSS_ACR_USERNAME
fi
if [[ -z "${TSS_ACR_PASSWORD:-}" ]]; then
  read -r -s -p "Aliyun ACR password: " TSS_ACR_PASSWORD
  echo
  export TSS_ACR_PASSWORD
fi
if [[ -z "$TSS_ACR_USERNAME" || -z "$TSS_ACR_PASSWORD" ]]; then
  echo "Both ACR username and password are required." >&2
  exit 1
fi

# 1) 创建 / 更新 docker-registry secret（幂等）
kubectl -n "$namespace" create secret docker-registry "$secret_name" \
  --docker-server="$server" \
  --docker-username="$TSS_ACR_USERNAME" \
  --docker-password="$TSS_ACR_PASSWORD" \
  --dry-run=client -o yaml | kubectl apply -f -

# 2) 把 secret 挂到训练 ServiceAccount。
# imagePullSecrets 列表按 name 做战略合并：已存在则不变，不存在则追加，保留已有其他项。
kubectl -n "$namespace" patch sa "$service_account" --type=strategic \
  -p "{\"imagePullSecrets\":[{\"name\":\"$secret_name\"}]}" \
  >/dev/null

echo "Done. Secret '$secret_name' attached to SA '$service_account' in namespace '$namespace'."
