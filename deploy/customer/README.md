# 甲方单机/扩容部署入口（D2a 配置准备已实现）

本目录面向从零安装的客户服务器，不依赖实验室机器名称、原私有仓库权限或 GitHub Runner。
继续使用当前 kubeadm + containerd + Docker Compose 架构，不另建一套业务服务。

**当前提供只读预检、安装配置生成、镜像归档校验/压缩和锁定镜像清单；还不是可直接安装的完整交付包。** 最新交付方式为“基础环境联网安装 + 项目镜像导入”，不再要求整套环境完全离线。12 个项目镜像包含新增的 GPU 推理镜像；联网安装步骤及新装复演尚未完成，不应交给甲方当作一键安装器。镜像制作、接收和导入衔接见 [PROJECT_IMAGES.md](PROJECT_IMAGES.md)。

## 已确认要求

- 客户服务器已安装 Ubuntu 22.04 及以上。本轮不准备 Ubuntu 系统安装镜像、不重装/升级系统，也不继续适配 20.04；不能凭系统版本号保证任意内核/驱动均可运行。
- 一台节点可同时承担控制、平台服务、存储和计算。后续新增计算节点，不搬迁原数据库和用户资产。
- 不需要 GitHub 自动部署工具；交付源码、项目相关镜像、配置模板和手册。用户已确认 Kubernetes 相关环境可联网安装，不再准备其离线安装包或基础组件镜像归档。先检查已有 Kubernetes、容器运行时和 NVIDIA 驱动/容器工具，仅补齐实际缺项，不为所有 Ubuntu 版本预制整套系统环境。
- 只在预留 CPU/内存后开放训练资源；显存预算仍为 PyTorch 软限制。

## 镜像交付与联网安装分工（最新范围）

以下数量来自当前锁文件；本批已完成项目镜像导出，不表示新装部署成功。每个用途只保留当前锁定版本。

| 类别 | 数量 | 获取方式 |
| --- | --- | --- |
| 前端、后端 | 2 | 交付项目镜像 |
| CV/NLP 的 CPU/GPU 训练运行时 | 4 | 交付项目镜像，保留 CPU 和 GPU 两种能力 |
| 当前 CPU 推理运行时 | 1 | 交付项目镜像，不宣称已有 GPU 推理 |
| 项目 MLflow-lite | 1 | 交付项目镜像 |
| 平台使用的 PostgreSQL、Redis、MinIO | 3 | 默认随项目交付，保证已验证版本与本地别名一致 |
| Kubernetes 核心、DNS、网络、Metrics Server、NVIDIA Device Plugin、DCGM 等 | 13 | 安装时按锁定版本/摘要联网获取，不默认打包 |

项目包默认合计 **12 个镜像（Docker 5 个、项目 containerd 7 个）**。把 PostgreSQL/Redis/MinIO 也随包交付是沿用现有五服务部署的最小变动建议，不表示它们由项目自研。联网安装基础环境仍需版本锁、下载源和摘要校验；下载失败停止并明确提示，不静默改用最新版或改动现有服务。

`image-catalog.json` 目前是 **25 个镜像的完整运行依赖清单**，不是“全部必须导出的镜像列表”：`purpose=kubernetes` 的 13 项在线获取，其余 12 项纳入项目包。`image_bundle.py` 已落实此分工并校验归档内容；安装入口还须落实正确运行时与运行别名。

现有生成清单保留 `imagePullPolicy: Never`。兼容新交付方式的最小方案是：安装阶段显式联网预拉基础组件固定摘要到项目 containerd 的 `k8s.io`，核验并保留清单要求的运行名称后再启动；项目镜像则从交付包导入。不能只去掉归档却漏掉预拉步骤，也不能仅拉 digest 而漏掉清单使用的标签。每个新增节点也要完成其所需镜像准备；“允许联网”不等于客户已能访问全部源站，首次安装仍需核对连通性。

## 先做只读检查

将本目录发给服务器管理员，在目标服务器执行（系统需有 Python 3.8 及以上）：

```bash
python3 preflight.py --data-path /srv/tss-AIplatform
```

该命令不安装、不改驱动、不改网络、不创建目录、不停止服务、不扫描用户文件内容。
报告只包含系统版本、CPU/内存、指定路径所在磁盘空闲量、GPU 型号/驱动和必要工具是否存在；不包含密码、令牌或进程命令行。
不要先创建或格式化磁盘来迎合示例路径。`--data-path` 填管理员实际计划使用的项目目录；路径不存在时检查其最近存在的父目录。

返回值为 0 只表示本阶段基础兼容性检查通过，**不代表项目包完整或平台可验收**。还需安装阶段核对项目包、联网依赖、固定版本、端口、网段、凭据、资源预留和真实 GPU 训练。

## 生成客户配置

1. 复制 `node.example.json` 到本机私有工作目录，填写真实地址、节点名、目录与资源预留。示例 `192.0.2.10` 不是客户服务器地址，不能照抄安装。
2. 在客户机器用已填写的配置运行只读检查：`python3 deploy/customer/preflight.py --config /path/to/node.json`。会额外核对真实 IP、CPU、内存和端口；检查失败不安装。
3. 在源码根目录生成到一个**不存在的新目录**（其父目录须已存在）：

```bash
python3 deploy/customer/deployment_plan.py --config /path/to/node.json --output /path/to/new-plan
python3 deploy/customer/deployment_plan.py --verify-output /path/to/new-plan
```

生成器只写 `--output` 指定的新目录，不修改 JSON 中填写的目标路径。它使用 Python 3.8 标准库，不要求甲方安装额外 Python 包。JSON/YAML 文件均为 UTF-8。

| 文件 | 用途 |
| --- | --- |
| `node.json`、`PLAN_COMPLETE.json`、`SHA256SUMS` | 保存本次参数、完整文件列表与内容校验；缺文件、额外文件、修改或半成品均拒绝 |
| `containerd.toml`、`*-containerd.service` | 项目独立数据、socket 和仓库配置，不覆盖系统默认 containerd 配置 |
| `var-lib-kubelet.mount`、`kubelet-dependencies.conf` | 标准 `/var/lib/kubelet` 绑定到项目盘，保留显卡插件发现路径，配置开机依赖 |
| `kubeadm-init.yaml`、`kubelet-config.json` | 控制/计算同机的初始化与预留；默认保留标准控制面污点，验收后按节点开放计算 |
| `compose.yml`、`platform.env` | 复用原五个服务，改为客户名称/路径、严格离线拉取策略；不含密码 |
| 网络服务、RBAC、前端、Metrics、NVIDIA、DCGM 清单 | 复用当前受审阅模板；GPU 开关控制是否生成 GPU 组件 |
| `image-catalog.json` | 25 个固定摘要的完整依赖清单；其中项目包 12 个、联网基础组件 13 个，不是全部要打包 |

每个镜像只有一个目标版本；清单中保留的原仓库名字属于本地镜像别名，必须导入对应名称，**不表示客户需要访问该私有仓库**。镜像归档可复用层，但导入 Docker 不等于已导入 Kubernetes 的 containerd。

更改参数后重新生成新目录，不手改已校验输出。校验和用于发现损坏/漏文件，不替代发布方通过可信渠道提供的整包校验和或签名。

安装必须使用生成的原始 `containerd.toml`，不要用 `containerd config dump` 的输出覆盖它。containerd 2.2.1 的展示结果会合并默认 `imports` 字段，但实际加载的是输入文件声明的导入项；原配置明确为 `imports=[]`。本轮通过源码及只读系统调用跟踪确认没有访问默认片段目录，仓库配置另显式限定在 `/etc/<cluster_name>/certs.d`。[加载行为依据](https://github.com/containerd/containerd/blob/v2.2.1/cmd/containerd/server/config/config.go)

## 新增计算节点

沿用同一配置结构，保持 `cluster_name`、控制面入口、Pod/Service 网段与原集群一致，将 `role` 改为 `worker`，填新节点 IP、名字、项目路径和适合该机器的预留。CPU 节点可设 `gpu_enabled=false`。

工作节点不会生成 `kubeadm-init.yaml`、平台服务或管理员凭据，只生成运行时、join 模板、节点资源预留 patch 和加入说明。短期 token 与 CA hash 必须由客户自己的控制面生成并安全传递，不能使用实验室值；升级也应保留本节点 patch。

## 假设与当前限制

- 当前部署入口为 IPv4、单控制节点、linux/amd64；用户指定 Ubuntu 22.04+ 为前置系统范围，不等于所有版本已验收。
- 示例预留系统 5 核/8 GiB（含五个 Compose 服务）、Kubernetes 1 核/2 GiB，另有 500 MiB 内存驱逐余量；是保守起点，不是甲方硬件容量，也不硬限制系统服务所在 cgroup。客户预检用实测容量判断是否容得下。[资源预留依据](https://kubernetes.io/docs/tasks/administer-cluster/reserve-compute-resources/)
- 首台控制节点保留标准 `control-plane:NoSchedule`，以免自定义暂存污点挡住 CoreDNS；最终只开放指定节点，不全局清除污点。[kubeadm 配置依据](https://kubernetes.io/docs/reference/config-api/kubeadm-config.v1beta4/)
- Metrics Server 继承当前内网的 `--kubelet-insecure-tls` 兼容配置；它跳过 kubelet 服务证书校验，需把 10250 网络访问限制在集群内。不代表关闭 API Server/RBAC 校验；企业要求严格 TLS 时应在后续独立步骤配置受信任 kubelet 证书，不能默默改成公网暴露。
- 本阶段未生成 Calico VXLAN 最终清单；目录内旧双节点安装脚本不可直接运行。Calico 上游源码、哈希和固定版本已在目录/清单里索引，联网获取与客户适配留在 D2b 后续。
- 权重缓存沿用当前默认关闭的状态；缓存目录、策略 ConfigMap 和功能验收是后续安装检查项，不因生成文件就宣称启用。
- `preflight.py` 的检查不覆盖软件版本组合、所有路由冲突或完整磁盘容量预算；这些需要 D2b 包准备和空机检查。

## 维护者测试

运行工具本身不依赖 PyYAML；只有源码测试用它独立解析生成 YAML，避免只检查字符串而漏掉缩进错误：

```bash
python3 -m pip install 'PyYAML==6.0.3'
python3 -m unittest discover -s deploy/customer -p 'test_*.py' -v
```

同一测试已加入后端 CI。测试只生成并清理专用临时文件，安装检查本身不创建目标数据目录。

## D2b 还需完成的安装材料

1. 12 个项目镜像按四组归档，保留校验和及来源/运行别名；交付前须完成新 GPU 推理镜像的导出校验和新运行时导入复演。Ubuntu 由客户预装。基础环境先检查已装版本，缺项按明确步骤联网安装；13 个基础组件镜像按固定摘要联网预拉，不准备整套环境离线包。
2. 新装前确认目录、地址、网段和端口，拒绝复用不属于本项目的 Kubernetes/数据目录。
3. 安装隔离的运行时并初始化单节点控制面；只对明确选择的节点开放计算，不改现有内网集群的控制节点策略。
4. 安装网络、Metrics Server、NVIDIA Device Plugin、GPU 监控；生成最小权限后端凭据。
5. 启动 PostgreSQL/Redis/MinIO/MLflow/后端/前端；随机生成新凭据，不复制实验室数据库或超级管理员密码。
6. 核对 CPU/内存预留、动态硬件、CPU/GPU 训练、推理、重启恢复；新增工作节点时重复镜像/驱动/监控/缓存准备。
7. 将检查输出、执行命令和结果写入部署记录，再编制最终逐步手册。

Ubuntu 22.04 及以上仍需核对内核、cgroup v2、NVIDIA 驱动与容器工具；不自动修改系统环境。参见后端 `doc/delivery-sop.md` 的支持边界。
