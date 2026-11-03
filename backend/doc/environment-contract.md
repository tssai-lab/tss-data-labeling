# TSS Platform 环境契约（S0-ENV-01）

> **任务 ID**：S0-ENV-01（阶段 0-A）  
> **更新日期**：2026-07-12  
> **适用范围**：`/opt/tss-platform` 本机开发/联调环境  
> **目标**：统一 Backend、Frontend、PostgreSQL、MinIO、MLflow、kind 的地址、端口、配置来源与健康检查入口。

---

## 1. 架构总览

```text
浏览器 (:8000)
  ├─ /api/*        → 宿主机 Backend :8080
  └─ /mlflow-api/* → 宿主机 MLflow :5000（重写为 /ajax-api）

Backend (:8080, systemd)
  ├─ PostgreSQL :5432
  ├─ MinIO API :9010
  ├─ MLflow :5000
  └─ kubectl → kind (:16443) → tss-training Namespace → Job/Pod

Worker Pod（tss-training）
  ├─ tss-backend:8080   → 宿主机网关 → Backend :8080
  ├─ tss-minio:9000     → 宿主机网关 → MinIO :9010
  └─ tss-mlflow:5000    → 宿主机网关 → MLflow :5000
```

**原则**：PostgreSQL、MinIO、MLflow、Backend 运行在**宿主机**（Docker 或 systemd）；计算平面在 **kind** 单节点集群；训练 Pod 通过 EndpointSlice 经 Docker `kind` 网络网关访问宿主机服务。

---

## 2. 端口与地址矩阵

### 2.1 宿主机（开发者 / Backend / 浏览器代理目标）

| 组件 | 监听地址 | 容器名 | 说明 |
|------|----------|--------|------|
| PostgreSQL | `127.0.0.1:5432` | `tss-postgres` | 主库 `tss`；推理库 `tss_inference`（可选） |
| MinIO API | `127.0.0.1:9010` | `tss-minio` | 容器内 `9000`，映射到宿主机 `9010` |
| MinIO Console | `127.0.0.1:9011` | `tss-minio` | Web 管理界面 |
| MLflow | `127.0.0.1:5000` | `tss-mlflow` | Tracking Server |
| Backend | `127.0.0.1:8080` | — | `tss-backend.service`（JAR） |
| Frontend 开发 | `127.0.0.1:8000` | — | `npm run dev` / `serve:prod` |
| kind API Server | `127.0.0.1:16443` | `tss-training-control-plane` | 仅 kubectl 使用 |

### 2.2 Kubernetes Pod 内（`tss-training` Namespace）

| Service 名 | Pod 内访问 | 实际转发 | 用途 |
|------------|------------|----------|------|
| `tss-backend` | `http://tss-backend:8080` | 宿主机网关 → `:8080` | 回调、API |
| `tss-minio` | `http://tss-minio:9000` | 宿主机网关 → `:9010` | 对象存储 |
| `tss-mlflow` | `http://tss-mlflow:5000` | 宿主机网关 → `:5000` | 实验跟踪 |

> **注意**：Service `port` 与 EndpointSlice `port` 在 MinIO 上映射为 Service `9000` → 网关 `9010`；Backend/MLflow 的 Service 端口与宿主机一致。

宿主机网关 IP 由 `bootstrap-local-kind.sh` 从 Docker `kind` 网络自动探测，写入 `k8s/local/host-services.template.yaml` 渲染后的 EndpointSlice。

### 2.3 工具链路径

| 工具 | 路径 | 版本 |
|------|------|------|
| kind | `/opt/tss-platform/.tools/bin/kind` | 0.32.0 |
| kubectl | `/opt/tss-platform/.tools/bin/kubectl` | 1.34.8 |
| 项目 kubeconfig | `/opt/tss-platform/k8s/.kube/config` | — |

---

## 3. systemd 服务（Backend）

| 项 | 值 |
|----|-----|
| 单元名 | `tss-backend.service` |
| 单元文件 | `/etc/systemd/system/tss-backend.service` |
| 工作目录 | `/opt/tss-platform/backend` |
| 启动命令 | `/usr/bin/java -jar /opt/tss-platform/backend/target/tss-backend-1.0.0.jar` |
| 环境文件 | `/opt/tss-platform/.env.backend`（`EnvironmentFile=-`，缺失不阻断启动） |
| 日志 | `/opt/tss-platform/backend/logs/backend-daemon.log` |
| 重启策略 | `Restart=on-failure`，间隔 5s |

**单元内硬编码环境变量**（不在 `.env.backend` 中）：

| 变量 | 值 | 说明 |
|------|-----|------|
| `TRAINING_K8S_ENABLED` | `true` | 启用 K8s 训练执行器 |
| `TRAINING_K8S_AUTO_CREATE` | `true` | 启动时自动引导 kind |
| `TRAINING_K8S_VERIFY_ON_STARTUP` | `false` | 启动时不跑连通性 Job |
| `SPRING_FLYWAY_VALIDATE_ON_MIGRATE` | `false` | 迁移时不强制 validate |

**常用运维命令**：

```bash
sudo systemctl status tss-backend.service
sudo systemctl restart tss-backend.service
sudo journalctl -u tss-backend.service -n 50 --no-pager
tail -f /opt/tss-platform/backend/logs/backend-daemon.log
```

---

## 4. 环境变量契约

### 4.1 `.env.backend`（Backend 敏感配置，勿提交、勿在日志中打印值）

路径：`/opt/tss-platform/.env.backend`

| 变量名 | 用途 | 默认/示例格式 |
|--------|------|---------------|
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC | `jdbc:postgresql://127.0.0.1:5432/tss` |
| `SPRING_DATASOURCE_USERNAME` | 数据库用户 | 见本地 `.env.backend` |
| `SPRING_DATASOURCE_PASSWORD` | 数据库密码 | 见本地 `.env.backend` |
| `MINIO_ENDPOINT` | MinIO API | `http://127.0.0.1:9010` |
| `MINIO_ACCESS_KEY` | MinIO 访问密钥 | 见本地 `.env.backend` |
| `MINIO_SECRET_KEY` | MinIO 秘密密钥 | 见本地 `.env.backend` |
| `MINIO_BUCKET` | 默认桶 | `models` |

### 4.2 `application.yml` 可覆盖项（未写入 `.env.backend` 时取默认值）

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `SPRING_PROFILES_ACTIVE` | `default` | Spring Profile |
| `TRAINING_MLFLOW_ENABLED` | `true` | MLflow 集成开关 |
| `TRAINING_MLFLOW_TRACKING_URI` | `http://127.0.0.1:5000` | Backend 连 MLflow |
| `TRAINING_MLFLOW_EXPERIMENT_NAME` | `tss-training` | 实验名 |
| `TRAINING_K8S_CLUSTER_NAME` | `tss-training` | kind 集群名 |
| `TRAINING_K8S_CLIENT_MODE` | `kubectl` | 训练 Job 控制通道：`kubectl` 或 `fabric8`；同一进程只启用一种，不自动回退 |
| `TRAINING_K8S_CLIENT_REQUEST_TIMEOUT_SECONDS` | `120` | 训练 Job 客户端单次请求超时，允许 1--3600 秒 |
| `TRAINING_K8S_NAMESPACE` | `tss-training` | 训练 Namespace |
| `TRAINING_K8S_KUBECONFIG` | `k8s/.kube/config` | 相对 `backend/` 工作目录 |
| `TRAINING_K8S_KIND_PATH` | `.tools/bin/kind` | 相对项目根 |
| `TRAINING_K8S_KUBECTL_PATH` | `.tools/bin/kubectl` | 相对项目根 |
| `TRAINING_K8S_BACKEND_SERVICE_URL` | `http://tss-backend:8080` | Pod 内 Backend 地址 |
| `TRAINING_K8S_MINIO_SERVICE_URL` | `http://tss-minio:9000` | Pod 内 MinIO 地址 |
| `TRAINING_K8S_MLFLOW_SERVICE_URL` | `http://tss-mlflow:5000` | Pod 内 MLflow 地址 |
| `TRAINING_K8S_INTERNAL_CALLBACK_TOKEN` | `tss-internal-callback-dev` | Worker 回调 Token |

完整列表见 `backend/src/main/resources/application.yml` 中 `training.kubernetes` 与 `minio` 段。

### 4.3 前端开发代理环境变量

| 变量名 | 作用域 | 说明 |
|--------|--------|------|
| `DEV_API_TARGET` | `npm run dev` | 覆盖 `config/proxy.ts` 中 `/api/` 目标，**本机联调必须设为** `http://127.0.0.1:8080` |
| `DEV_MLFLOW_TARGET` | `npm run dev` | 覆盖 `/mlflow-api/` 目标，本机联调设为 `http://127.0.0.1:5000` |
| `BACKEND_PROXY_TARGET` | `npm run serve:prod` | 生产静态服务后端代理 |
| `MLFLOW_PROXY_TARGET` | `npm run serve:prod` | MLflow 代理（含 `/ajax-api` 路径） |
| `HOST` / `PORT` | `serve:prod` | 监听地址，默认 `0.0.0.0:8000` |

前端代理详情见 `frontend/docs/环境契约与代理配置.md`。

---

## 5. kubeconfig 与 kind

### 5.1 使用方式

```bash
export KUBECONFIG=/opt/tss-platform/k8s/.kube/config
export PATH=/opt/tss-platform/.tools/bin:$PATH

kubectl get nodes
kubectl get svc -n tss-training
```

### 5.2 创建或修复集群

```bash
cd /opt/tss-platform
./backend/scripts/k8s/bootstrap-local-kind.sh
```

幂等行为：集群不存在则创建；已存在则刷新 kubeconfig 并重新应用 Namespace、配额与宿主机 Service 映射。

### 5.3 Pod 连通性验证（可选深度检查）

```bash
./backend/scripts/k8s/verify-local-kind.sh
```

成功时 Job 日志含：`TSS training dependencies are reachable`。

---

## 6. 健康检查

### 6.1 一键检查（推荐）

```bash
cd /opt/tss-platform
./backend/scripts/env-health-check.sh
```

输出示例（每项独立 PASS/FAIL，不打印凭据）：

```text
[PASS] PostgreSQL  127.0.0.1:5432  accepting connections
[PASS] MinIO       127.0.0.1:9010  /minio/health/live → 200
[PASS] MLflow      127.0.0.1:5000  HTTP → 200
[PASS] Backend     127.0.0.1:8080  /api/user/current-user → 401 (service up)
[PASS] kind        cluster=tss-training  1/1 node(s) Ready; ns=tss-training svc=3
---
Result: 5/5 passed
```

### 6.2 脚本选项

| 选项 | 说明 |
|------|------|
| （默认） | 检查 PG、MinIO、MLflow、Backend、kind 节点 Ready |
| `--with-pod-connectivity` | 额外运行 `verify-local-kind.sh`（约 1–3 分钟）。若集群 LimitRange 高于连通性 Job 的 request，可能失败；此时以默认 kind 检查为准 |
| `--skip-kind` | 跳过 kind 检查 |

### 6.3 单项手工探测（无凭据泄露）

```bash
# PostgreSQL
docker exec tss-postgres pg_isready -U postgres

# MinIO
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:9010/minio/health/live

# MLflow
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:5000/

# Backend（未登录应返回 401，表示服务存活）
curl -fsS -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/user/current-user

# kind
export KUBECONFIG=/opt/tss-platform/k8s/.kube/config
/opt/tss-platform/.tools/bin/kubectl get nodes
```

### 6.4 前端可达性（开发模式）

```bash
cd /opt/tss-platform/frontend
DEV_API_TARGET=http://127.0.0.1:8080 \
DEV_MLFLOW_TARGET=http://127.0.0.1:5000 \
npm run dev
```

浏览器访问 `http://127.0.0.1:8000`，登录页可打开且 `/api/user/login` 经代理到达本机 Backend。

---

## 7. 新人 30 分钟检查清单

| 步骤 | 命令 / 动作 | 预期 |
|------|-------------|------|
| 1 | `docker ps` 见 `tss-postgres`、`tss-minio`、`tss-mlflow` | 容器 Up |
| 2 | `systemctl is-active tss-backend.service` | `active` |
| 3 | 确认 `/opt/tss-platform/.env.backend` 存在 | 文件存在（不查看密码内容） |
| 4 | `./backend/scripts/env-health-check.sh` | `5/5 passed` |
| 5 | `export KUBECONFIG=.../k8s/.kube/config && kubectl get nodes` | `Ready` |
| 6 | 前端 `DEV_API_TARGET=http://127.0.0.1:8080 npm run dev` | `:8000` 可访问 |

---

## 8. 相关文档

| 文档 | 路径 |
|------|------|
| 本机 kind 训练 SOP | `backend/doc/local-kind-training-sop.md` |
| CPU 训练 SOP | `backend/doc/local-cpu-training-sop.md` |
| 研发推进总规划 | `docs/研发推进总规划.md` |
| 前端代理配置 | `frontend/docs/环境契约与代理配置.md` |
| 后端架构 | `backend/doc/technical-design.md` |

---

## 9. 修订记录

| 版本 | 日期 | 说明 |
|------|------|------|
| v1.0 | 2026-07-12 | S0-ENV-01：初版环境契约与健康检查脚本 |
