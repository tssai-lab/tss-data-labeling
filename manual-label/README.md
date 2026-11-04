# 大文件标注后端

独立 Python 标准库服务，不修改原自动化标注后端。原有训练平台只用于验证登录令牌。

## 本机运行

```bash
cd server
python3 app.py
```

默认绑定 127.0.0.1:8013，数据保存在 server/data，使用 Python 3.10 或以上版本。
前端通过 /label-api 代理访问。默认认证接口为本地训练后端 http://127.0.0.1:8080/api/user/current-user；每次请求均验证登录与项目归属，数据按用户隔离。

## 流程

1. 用户选择 ZIP、TAR、TAR.GZ 或单个图像/时序文件，创建项目。
2. 浏览器分 8 MB 上传，最多重试三次；可暂停，重新选择原文件继续。继续前核对已传文件前缀。
3. 服务器保留 original.upload，单个后台任务解包，支持最多三层嵌套。只索引图像与有效数值 CSV/DAT；跳过说明与 VisA Masks。
4. 页面每页50个文件，打开文件才读取内容。图片用 Blob URL，切换后释放；时序每次只读取一个运行批次。
5. 标注保存到 SQLite，与原数据分开；每条记录有版本号，防止多页面静默覆盖。
6. 导出服务器标注 JSON，含源文件相对名称，不包含所有原始数据。它是结果文件，不能作为旧版完整浏览器项目导入。

## 容量与限制

- LABEL_MAX_UPLOAD_GB 默认10GB；LABEL_MAX_EXPANDED_GB 默认20GB（含嵌套中间归档展开字节）。受剩余磁盘空间限制。
- 不在浏览器中一次加载整个归档。不是取消一切容量约束。
- 最多20000个待标注文件，每层归档最多30000个目录条目；拒绝路径穿越、符号链接、设备文件。
- 单个时序文件最多64MB、50000采样点。适合按运行批次拆分的TEP；尚不支持单条超长时序的窗口查询。
- 以本次原始数据测得：datasets.zip 能解析为10821张图像及44份时序，约51秒（本机，非服务器性能承诺）。
- 服务重启时，未传完的文件可继续；解包中的项目标记为失败，可重新处理。无自动清理或删除原始数据任务；重复处理会保留旧提取文件，运维需关注磁盘。
- 项目列表当前显示最近100个项目。是单实例演示服务，不是分布式上传平台。

## 服务器部署准备

compose.yml 为独立后端服务，挂到现有 tss-trunk-8001_default 网络；不开放公网后端端口。数据卷 manual-label-data 持久化原始文件和数据库。
构建启动后，在现有前端 nginx 的 server 中加入 nginx-location.conf，再部署新前端。原前端 nginx 配置与镜像需先备份。

```bash
docker compose up -d --build
```

首次构建需要取得 python:3.13-slim 镜像。部署前核对现有 Docker 网络名称，不操作训练后端、数据库或原自动化标注服务。

## 本次线上运行记录

2026-10-22 已上线，目录 /opt/tss-manual-label-service，数据卷 tss-manual-label-service_manual-label-data。公网入口复用8001，由nginx代理/label-api，新后端不单独暴露公网端口。镜像源不可用，本次用已缓存 tss-platform-mlflow:local 中的Python3.11作为基础，重建命令为 LABEL_BASE_IMAGE=tss-platform-mlflow:local docker compose up -d --build。原MLflow实例不变。

## 本地 Trunk 恢复（2026-11-02）

来源：TSSAIPlatform 的 demo 分支 `474c6ce`。仅接回手动标注，不恢复自动化标注模块。

运行 `/home/leriger/tss-trunk-local/start-manual-label.sh`，监听 127.0.0.1:8013；认证使用本地训练后端，数据保存到 `/home/leriger/tss-trunk-local/manual-label-data`。前端通过 `/label-api/` 访问。服务器项目数据与账号未复制到本地。上面的 Compose 仅是原服务器部署参考，本次没有执行。
