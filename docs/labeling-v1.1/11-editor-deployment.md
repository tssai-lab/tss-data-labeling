# Label Studio与平台编辑器部署说明

## 当前状态

本文件是部署操作说明，不代表此次已执行服务器部署。本地回归与服务器正式验收分开。源码推送到功能分支不应直接触发业务环境迁移。

## 版本与既有服务器记录

Label Studio Community镜像标签1.23.2，源码提交df96d79183156e556d91825d15fa7721f1145e1a。官方镜像索引摘要sha256:afcc516a22775a39d0d66f4c2ddc95d01be3e3d3862b21fcf4f9de34b4ad4e12。内部Python元数据1.23.0是已核实的上游标记差异，按镜像摘要和源码提交识别。

此前服务器47.111.225.144部署目录/opt/tss-label-studio，容器tss-label-studio-editor-1，数据卷tss-label-studio_data，映射127.0.0.1:8085到容器8080。本次未复核其当前运行状态，也未更换该实例。

```text
服务器程序 → 127.0.0.1:8085 → Docker映射 → LS容器8080
本机浏览器 → SSH本地18085 → 服务器127.0.0.1:8085（仅隧道运行时）
```

127.0.0.1表示请求发起环境自身。本机8085的LS与服务器8085不是同一个实例；平台后端在容器中时，应使用正确的容器网络地址，不能把容器自身127.0.0.1当成宿主机。

既有服务状态检查在服务器执行：

```sh
cd /opt/tss-label-studio
docker compose ps
```

新建环境可使用官方镜像，必须配置独立持久数据卷、仅内部可访问的端口和受控服务账号。不要重用共享管理员凭据给标注员；不要删除既有数据卷。旧环境的配置文件可能含凭据，不复制进源码或文档。

## 平台后端配置

JDK17，PostgreSQL，沿用原数据库及身份服务配置。项目模式需要：

```text
LABELING_PROJECT_EDITOR_ENABLED=true
LABELING_EDITOR_PROJECTS=明确允许的项目UUID
LABELING_EDITOR_BASE_URL=http://127.0.0.1:8085
LABELING_EDITOR_USERNAME=由受控环境提供
LABELING_EDITOR_PASSWORD=由受控环境提供
LABELING_EDITOR_TICKET_SECRET=独立随机密钥，至少32字符，仅后端保存
```

默认关闭，项目白名单默认为空。生产不要用测试通配符*。签发密钥用于HMAC一次性票据，不与密码混用；需稳定保管，轮换使未使用入口失效，已建立Cookie会话仍按有效期和业务权限检查。

会话表校验字段保存ticket和Cookie摘要；幂等响应不保存含ticket的editorUrl，重试时由签发密钥重建未使用的有效入口。现有数据库中旧版本response_body可能包含历史短时入口，按保留策略处理，不声称自动完成历史数据清除。

V5/V6先由业务后端核对迁移编号及现有等价表。保持已执行迁移不变，不在共享库手工重复建表。V6不重新创建项目或上传图片，不生成任务包/领取。

## 编辑器静态资源与构建

编辑组件不是从公网CDN加载，而是打包到平台JAR。源于上述固定LS源码提交，构建产物放在：

```text
labeling-service/src/main/resources/static/editor-assets/
```

该生成目录不提交Git。完整运行资源须包括main.js、main.css、动态chunk、图片字体等，并保留上游许可证。仅有Java代码或Maven构建成功，不代表编辑器资源齐全。

从固定上游源码的web目录，用Node22、Yarn1.22.22和上游锁文件构建：

```sh
corepack yarn install --frozen-lockfile
NODE_ENV=production MODE=standalone NX_DAEMON=false NODE_OPTIONS=--max-old-space-size=4096 corepack yarn nx run editor:build:production
```

将dist/libs/editor完整运行资源及上游LICENSE复制到上述目录，然后：

```sh
mvn -f labeling-service/pom.xml clean verify
mvn -f labeling-service/pom.xml package
```

检查JAR内BOOT-INF/classes/static/editor-assets的main.js、main.css及动态资源，且不包含已移除的AssignmentEditorService等旧类。现有自动部署工作流尚未包含上游资源准备，需要在合并部署前落实固定资源制品或CI构建步骤；不得直接合并后依赖开发机器上的缓存。

## 网关与前端

前端与/api/labeling/v1/project-editor-ui、editor-assets、项目API应同源。正式环境使用HTTPS，核对后端识别代理HTTPS及Secure Cookie，允许同源窗口/iframe，避免SPA吞掉资源路由。launch查询中的ticket脱敏，禁止记录Token、Cookie和服务密码。

本地验证可将标注代理指向本机8088，身份代理指向已授权的统一身份服务；不能把本地验收地址当成团队可访问的服务。前端入口使用返回的editorUrl，不直接打开LS完整后台。

## 备份、回滚与故障

上线前备份业务库、原始图片、LS数据卷及受控配置。先关闭LABELING_PROJECT_EDITOR_ENABLED停止新会话/读写，再按团队流程恢复旧JAR，并验证Flyway对新增迁移的兼容性。保留新增表和数据卷，不通过删表回滚。

外部导入失败可能留下PENDING映射，需要核对LS任务、图片和规范后对账，不能盲目重建或直接改成READY。外部保存成功而平台落库失败时不显示成功，相同请求可按版本规则重试；异常外部答案需要对账。

共同验收：同源入口、实际账号/项目、分类保存与恢复、暂停/停用、错误提示、备份恢复。权限和数据契约由业务后端共同评审；未完成前只提交功能分支供审查。
