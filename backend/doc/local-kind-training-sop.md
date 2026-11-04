# TSS Platform 本机 kind/Kubernetes 训练环境 SOP

## 1. 目标

本 SOP 建立一个不会替换现有 Docker 基础服务的单节点开发集群：

```text
kind Kubernetes
  -> tss-training Namespace
  -> Kubernetes Job/Pod
  -> 宿主机 TSS Backend、MinIO、MLflow
```

PostgreSQL、MinIO、MLflow 和当前后端继续按原方式运行。训练 Pod 通过 Kubernetes Service 访问宿主机映射端口。

## 2. 固定版本

```text
kind: 0.32.0
Kubernetes: 1.34.8
kubectl: 1.34.8
```

本机是 Ubuntu 20.04 和 cgroup v1，因此暂不使用 kind 默认的 Kubernetes 1.36。后续生产服务器建议使用 Ubuntu 22.04/24.04 和 cgroup v2。

节点镜像使用 kind 官方发布的固定 digest。由于本机 Docker Hub 连接超时，配置通过 DaoCloud 镜像代理下载相同 digest 的镜像，不改变镜像内容标识。

工具安装在：

```text
/opt/tss-platform/.tools/bin
```

不会覆盖 `/usr/bin/kubectl`。

## 3. 创建或修复集群

确认基础服务已经启动：

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

执行：

```bash
cd /opt/tss-platform
./backend/scripts/k8s/bootstrap-local-kind.sh
```

脚本具有幂等性：

- 集群不存在时创建。
- 集群已存在时刷新项目专用 kubeconfig。
- 重复应用 Namespace、ResourceQuota、LimitRange、ServiceAccount。
- 自动识别 kind Docker 网络的宿主机网关地址。
- 将后端、MinIO、MLflow 映射为训练 Namespace 内的 Service。

项目 kubeconfig：

```text
/opt/tss-platform/k8s/.kube/config
```

使用时：

```bash
export KUBECONFIG=/opt/tss-platform/k8s/.kube/config
export PATH=/opt/tss-platform/.tools/bin:$PATH
```

## 4. 验证集群和依赖连通

```bash
cd /opt/tss-platform
./backend/scripts/k8s/verify-local-kind.sh
```

成功输出：

```text
TSS training dependencies are reachable
```

这个 Job 验证：

- Pod 可以被调度到 CPU 节点。
- Namespace 的 restricted Pod Security 可以通过。
- Pod 可以访问后端 8080。
- Pod 可以访问 MinIO 9010。
- Pod 可以访问 MLflow 5000。

## 5. 查看状态

```bash
kubectl get nodes -o wide
kubectl get all -n tss-training
kubectl describe resourcequota -n tss-training
kubectl describe limitrange -n tss-training
```

查看 Job：

```bash
kubectl get jobs,pods -n tss-training
kubectl logs job/tss-training-connectivity -n tss-training
```

## 6. 当前本机配额

```text
训练请求 CPU 总量：3
训练 CPU limit 总量：4
训练请求内存总量：8 GiB
训练内存 limit 总量：12 GiB
Pod 数量：10
Job 数量：20
```

单容器默认：

```text
request: 500m CPU / 512 MiB
limit: 2 CPU / 4 GiB
```

本机只有 4 核，不能把全部 CPU request 分配给训练，否则控制面和现有服务会失去运行空间。

## 7. 关于多用户

当前 `tss-training` 是本机开发 Namespace。下一阶段接入后端调度器后：

```text
一个训练任务 = 一个 Kubernetes Job
```

生产环境建议每个团队一个 Namespace，并为每个 Namespace 配置：

- ResourceQuota
- LimitRange
- ServiceAccount
- RBAC
- NetworkPolicy
- Kueue LocalQueue

本机 kind 默认网络插件不应被当成生产级多租户网络隔离。生产集群应安装支持 NetworkPolicy 的 Cilium 或 Calico。

## 8. 保留和删除

日常停止不需要删除集群。kind 节点是 Docker 容器，Docker 服务重启后通常可以继续使用。

只有明确要重建本机 K8s 时才执行：

```bash
/opt/tss-platform/.tools/bin/kind delete cluster --name tss-training
```

该命令只删除 kind 集群容器，不会删除：

- `tss-postgres`
- `tss-minio`
- `tss-mlflow`
- 它们的数据卷和目录

不要执行：

```bash
docker compose down -v
docker system prune --volumes
```

## 9. 图文一致性测试资产（fusion_logreg）

种子脚本 `backend/scripts/seed-consistency-test-assets.sh` 使用全量 `consistency_test_data.zip`（约 177MB）。

若仅需 `image_text_consistency_fusion_logreg` 训练，可生成最小 data 包（9 个预计算分数 JSONL，约 3.4MB，不覆盖全量包）：

```bash
backend/scripts/build-consistency-fusion-data-min.sh
# 默认输出 /opt/consistency_test_fusion_data_min.zip（不入库，本地生成）
```

Profile 与 data 包边界说明见 `backend/doc/training-profile-security.md`。
