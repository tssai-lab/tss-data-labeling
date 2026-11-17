# LS 源码标注服务发布与恢复

功能分支 `feat/backend_LS` 和目标为 `backend` 的 PR 先运行 build；仅 `backend` 分支的 push／手动触发运行依赖 build 的 deploy。前端对应 `feat/frontend_LS`／`frontend`。合并后的自动部署保留，功能分支不会执行 SSH。

## 发布包

构建使用 Java 17、Python 3.11。`ls-source-adapter/requirements.lock` 固定 Linux x86_64 的38个直接／间接依赖版本和 wheel SHA-256。修改依赖时同时更新 requirements.txt、lock 和验证记录；不在服务器从包索引临时解析依赖。

CI 下载锁定 wheels，在独立环境执行源码运行检查，运行 Maven verify，再生成 `target/labeling-release.tar.gz`。包内包含 JAR、Python 源码、原版模板资源、wheelhouse、哈希锁、运行检查和清单。清单绑定本次 Git 提交及逐文件摘要。再次解包、离线安装并验证后才上传；原始 ZIP、图片、数据库和已有结果不在发布包内。

## 部署前置条件

目标须为 Linux x86_64，提供 Python3.11（含 venv）、psql、systemd、curl，以及兼容的 pg_dump。支持系统 Python 或 `/opt/tss-labeling-service/runtimes/python3.11/bin/python3.11` 的私有 Python。备份前先查询数据库主版本；优先匹配宿主客户端，版本太旧时仅允许使用端口映射与标注库一致的 `tss-labeling-postgres` 容器客户端。远程数据库或端口不匹配时拒绝容器回退。wheelhouse 使用 manylinux wheels，须满足其系统 ABI。服务仍为 `tss-labeling.service`，JAR 入口 `/opt/tss-labeling-service/tss-labeling-service.jar`，现有健康端口8083。服务必须通过环境变量声明实际 PostgreSQL JDBC URL，迁移账号具备 labeling schema 的备份权限。单独的 Flyway 与应用数据库配置会被拒绝，须先明确备份范围。

独立 Python 的准备脚本为 `scripts/install-private-python.sh`：只接受固定文件名及官方 SHA-256 匹配的 CPython3.11.18 standalone 包，拒绝覆盖已有运行环境，不改系统 Python 或服务配置，不重启服务。必须由用户单独授权后手动执行，CI 和自动部署不调用此安装脚本。

用户在2026-11-16明确授权只读核验后，已确认目标是 Linux x86_64／glibc2.31，原系统Python3.8.10、标注服务账号tsslabeling、工作目录/opt/tss-labeling-service；标注库为127.0.0.1:5435的PostgreSQL16.13，宿主pg_dump12.22不兼容，匹配的tss-labeling-postgres容器内pg_dump16.13可用。独立Python安装另行取得明确授权，执行结果单独记录，不将此前本地结果视为服务器核验。

## 切换及失败恢复

1. 校验压缩包及所有清单摘要，拒绝越界路径、链接、额外文件和不匹配的源码版本。
2. 在 releases 下新建独立目录及 venv，仅使用包内 wheels 离线安装；运行模板、配置、结果校验、示例和真实 JSON 导出检查。失败时旧服务继续运行。
3. 保存原 JAR 和本脚本管理的 systemd 配置。从运行服务环境读取数据库连接，先停止标注服务，再备份 labeling schema。备份及其父目录仅管理员可读。
4. 原子切换 current、JAR 入口与 Python 配置，再启动服务。保留原存储目录、统一身份配置、数据库及其他服务配置。
5. `/api/labeling/v1/health/ready` 必须同时通过数据库查询及实际 LS 配置解析才算部署成功；原 `/health` 仍只表示 HTTP 可响应。
6. 失败时恢复原 JAR、current 和本脚本管理的配置，重启旧服务。保留新版本和备份供排查，不递归清理目录，也不自动降级数据库。

数据库恢复须先确认停写，并由管理员选择该次 backups 目录中的 labeling.dump；在独立恢复库演练后才决定恢复生产 labeling schema。恢复前备份故障时状态。源码升级是新增字段／表及扩展约束，已验证从有历史数据的 V14 到 V20，旧 V1—V14 SQL 的迁移校验和历史查询继续可用；这不替代目标服务器的恢复演练。

## 本地证据（2026-11-15）

- 后端完整62项：61通过、1项原有条件检查跳过；新增隔离数据库升级和依赖就绪检查通过。
- 发布边界6项：摘要、源码版本、目录穿越／链接、备份连接解析、原生客户端匹配及容器回退限制，均通过。
- 实际发布包217个负载文件、38个依赖；解包校验与 Linux/Python3.11 哈希锁依赖解析通过。当前本地预检包 revision 为全零，明确表示未提交候选包；CI 正式包使用 GITHUB_SHA。
- 解出的源码使用本机验证环境实际完成配置／结果校验、模板、示例和 JSON 导出。Windows 上没有执行 Linux systemd 切换，也没有执行远程部署；CI 会在 Linux 独立环境重新验证实际发布包。
- 前端独立依赖安装、生产压缩编辑器、整站生产构建通过；生产静态页10项检查、12张截图，覆盖时序／图像及1280／1440宽度，无页面脚本错误或失败的标注接口请求。
- 随后使用本机新端口8084运行实际解包的 JAR／源码，以8016的生产静态页完成两个新标记项目、四个真实小样本的导入—预览—配置—发布—领取—草稿恢复—完成提交—核查—固定成果—完成项目闭环。实际下载时序JSON／CSV及图像JSON／COCO，逐项核对标签、区间、框坐标、来源版本及引用资源SHA-256通过；未重新录像，原录像和原验收项目保留。证据为 `release-flow/acceptance-result.json` 和 `release-flow/deliverable-verification.json`。

证据在工作区 `.tools/identity-display-qa/`，不随代码提交或部署。
