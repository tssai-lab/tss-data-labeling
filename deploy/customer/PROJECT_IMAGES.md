# 项目镜像包：制作、接收与校验

本页是 D2b 镜像材料说明，不是完整安装手册。基础环境可联网安装；Ubuntu 由客户预装，范围为 22.04 及以上、linux/amd64。镜像包通过校验不等于单机安装、GPU 训练或重启恢复已经通过。

## 交付内容

每个业务用途只选当前锁定版本，合计 12 个镜像，分成四个压缩归档。分组内由原生导出工具复用相同镜像层，不另外逐个镜像重复打包。不引入跨组自定义层拼装格式，保持原生导入能力。

| 目录 | 镜像数 | 用途 | 目标运行时 |
| --- | --- | --- | --- |
| `platform/` | 5 | PostgreSQL、Redis、MinIO、MLflow-lite、后端 | Docker |
| `cpu/` | 3 | CV CPU 训练、NLP CPU 训练、CPU 推理 | 项目 containerd 的 `k8s.io` |
| `gpu/` | 3 | CV GPU 训练、NLP GPU 训练、GPU 推理 | 项目 containerd 的 `k8s.io` |
| `frontend/` | 1 | 前端 | 项目 containerd 的 `k8s.io` |

每组包含同名 `.tar.gz` 和 `GROUP_COMPLETE.json`。包根目录另有 `online-infrastructure.json`（13 个联网基础组件及清单来源）和最后生成的 `BUNDLE_COMPLETE.json`（完整文件集合、校验和与源码锁摘要）。四组缺一、归档损坏、源锁变化或额外文件均不能通过整包校验。

这是**镜像导出**，不是容器或服务器备份：不导出运行容器的环境变量、挂载卷、实验室数据库、账号和 Kubernetes 凭据；也不包含验收模型、数据集或训练代码资产包。客户新装需生成自己的凭据并单独导入验收资产。不能把正在运行的容器 `commit` 成镜像再交付。

## 接收方先校验，不先安装

接收对应版本的源码和完整镜像目录，先通过独立可信渠道核对发布方提供的 `BUNDLE_COMPLETE.json` SHA-256。只有包内自带的校验和，不能证明发送方身份，也不能抵御整包和校验和一起被替换。

在对应版本源码根目录执行：

```bash
sha256sum /path/to/project-images/BUNDLE_COMPLETE.json
python3 deploy/customer/image_bundle.py --verify /path/to/project-images
```

`/path/to/project-images` 换成接收后的实际目录。脚本会读取源码中的五份镜像锁，核对包的版本身份与全部归档内容校验和；只读、不拉镜像、不连接集群。输出 `VERIFIED 12 project images; no installation performed` 才表示包完整。

如果使用单独发送的校验工具，必须同时提供 `image_bundle.py`、`image_catalog.py` 和本批受信任的完整 `catalog.json`，运行：

```bash
python3 image_bundle.py --catalog /path/to/catalog.json --verify /path/to/project-images
```

单独的 `catalog.json` 也必须通过可信渠道校验，不可以临时改它来迎合一个校验失败的包。文件名为 `catalog.json` 的交付目录清单与配置生成器产出的 `image-catalog.json` 内容用途相同，均不包含镜像本体。不要给完成包内随意追加说明文件；文档和源码与它并列保存。

## 制作方流程与保护边界

1. 从本批源码锁生成完整目录清单，明确后端、前端、运行时版本；先核对运行时中的镜像内容，而非仅看同名标签。
2. 只对明确项目镜像做原生 `docker image save` 或 `ctr images export`；CPU/GPU 已存在且匹配锁的归档可复用。不要运行带拉取、改标签、清理副作用的旧导出流程来准备正在使用的共享集群。
3. 输出放到管理员指定的项目数据盘，先核对真实挂载点。工具检查的是**输出所在文件系统**，预留源归档大小的 1.1 倍加 10 GiB，不因根盘或别的磁盘空闲就通过；这不是权重缓存策略。
4. 用下面的只读归档检查与压缩工具逐组处理，`--output` 必须不存在，父目录须提前明确建立。源归档不允许并发替换；输入/输出不接受符号链接路径。工具不执行 Docker/containerd 操作。

```bash
python3 deploy/customer/image_bundle.py \
  --group cpu --source /path/to/cpu-runtime-amd64.tar \
  --output /path/to/project-images/cpu \
  --fingerprinter deploy/tss-aiplatform-internal/platform/scripts/image-runtime-fingerprint.py
```

`platform`、`gpu`、`frontend` 使用相同入口和各自原始 tar。压缩采用低压缩级别、4 MiB 流式块，避免把大包一次读入内存；在共享 Linux 机器上可由管理员加 `nice -n 15 ionice -c 3` 降低 CPU/磁盘优先级。仍有真实磁盘读写和网络传输开销，不是零资源占用。

5. 检查依据为：归档用途/别名准确、linux/amd64、config 精确摘要，及每一层实际字节的 `diff_ids`。只有 config 摘要不一致且锁已有受审阅运行指纹时，才采用项目既有指纹兼容规则；不能凭标签或包名跳过内容检查。仓库 manifest digest、config digest 与归档文件 SHA-256 是不同对象，不能要求三者互相相等。
6. 四组汇总到同一项目目录后，封存并再次校验：

```bash
python3 deploy/customer/image_bundle.py --finish /path/to/project-images
python3 deploy/customer/image_bundle.py --verify /path/to/project-images
sha256sum /path/to/project-images/BUNDLE_COMPLETE.json
```

只有四组全部完成且校验成功才生成整包完成标记。压缩/传输中断时不删除现场，也不覆盖原输出；检查失败目录后使用新的输出目录重做或明确恢复传输，完成后重新校验。禁止手补成功标记。`installation_verified=false` 是刻意保留的状态，不应手动改成已安装。

## 安装衔接（后续安装入口必须实现）

- `platform` 导入 Docker，其余三组导入项目独立 containerd 的 `k8s.io`。导入 Docker 不代表 Kubernetes 能找到镜像。
- 回执中同时保存 `source` 和 `runtime_ref`。原生归档可能只保留来源标签；导入后还必须按锁建立、核对运行名称，特别是 CPU/GPU 训练方案使用的 `@sha256:…` 名称。不能仅导入成功就启动，也不能盲目覆盖已有异版本别名。现有 CPU/GPU 导入脚本可作为行为依据，客户入口需适配而非直接运行实验室脚本。
- 本地 OCI 描述符因归档工具重序列化而不同于仓库 manifest 并不自动代表错误；应先核对实际 config/层与锁，再建立准确别名。不能把本地描述符差异当成允许任意镜像的理由。
- 基础环境 13 项镜像按锁定源/摘要联网预拉，并保留清单要求的本地名称；现有 `imagePullPolicy: Never` 不代替镜像准备。还需安装网络、Metrics Server、NVIDIA Device Plugin、DCGM 与 RuntimeClass。
- 新增计算节点复用同一份适用项目归档，不重新部署平台数据库；每个节点都要检查镜像、别名、驱动、资源预留及节点监控。
- 镜像运输体积不等于安装后容量。目标运行时还需内容库、解包层和业务数据空间；当前压缩包大小不能直接用作服务器磁盘最低要求。

完整安装流程、干净运行时导入、单机训练/推理、扩容和重启恢复仍由 D2 后续及 D3 手册收口。本页不授权对现有共享集群做这些变更。
