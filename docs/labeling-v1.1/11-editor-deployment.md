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

JDK17，PostgreSQL，沿用原数据库及身份服务配置。领取编辑器需要：

```text
LABELING_EDITOR_ENABLED=true
LABELING_EDITOR_PROJECTS=明确允许的项目UUID
LABELING_EDITOR_BASE_URL=http://127.0.0.1:8085
LABELING_EDITOR_USERNAME=由受控环境提供
LABELING_EDITOR_PASSWORD=由受控环境提供
LABELING_EDITOR_TICKET_SECRET=独立随机密钥，至少32字符，仅后端保存
```

默认关闭，项目白名单默认为空。生产不要用测试通配符*。签发密钥用于HMAC一次性票据，不与密码混用；需稳定保管，轮换使未使用入口失效，已建立Cookie会话仍按有效期和业务权限检查。

editor_session保存ticket和Cookie摘要；幂等会话响应不保存含ticket的editorUrl，重试时由签发密钥重建未使用的有效入口。业务idempotency_record保存同步结果响应，不含入口票据。

## 数据库迁移与旧验收库

已整合业务backend提交c26f90182ca2ff49d2b731ce1f09aaacf847d426，保留其V1—V5原文。唯一V5为V5__create_annotation_results.sql，负责annotation_draft及不可变提交。新增V6__create_assignment_editor.sql只建editor_mapping/editor_session、归属外键、唯一约束和应用账号SELECT/INSERT/UPDATE授权，不重复创建草稿/项目/图片。

本分支旧V5__create_editor_drafts.sql和V6__create_project_editor.sql已从生产迁移目录移除，原文件在本机.tools归档，未修改任何既有数据库。此前本地项目直标验收库已执行过这些旧版本，不能直接启动本版服务接管；Flyway会检测历史差异。保留旧库及备份，使用新的隔离库从业务V1—V5和新V6建立验收环境，旧数据迁移另行评审。禁止repair、手改checksum、删历史或用IF NOT EXISTS掩盖不兼容表。

目标环境若只执行了业务V1—V5，可按团队部署流程备份并升级到V6；实际执行前仍须核对flyway_schema_history、实际表和其他分支编号。配置服务的迁移账号执行，运行账号不授DDL或删除提交的权限。现有业务表及数据保留，不生成额外领取。

## 编辑器静态资源与构建

日常构建只需JDK17和Maven，不需开发机器的.tools、旧target、Node或上游源码。固定资源包约2.7MiB，作为第三方依赖随源码交付：

```text
labeling-service/third-party/label-studio/editor-assets-1.23.2.zip
labeling-service/third-party/label-studio/editor-assets-1.23.2.properties
labeling-service/third-party/label-studio/editor-source.json
```

这是此前固定源码提交构建并经过浏览器验证的产物，不是任意下载的最新文件。来源JSON记录版本、提交、构建环境和校验值。包中包含运行时JS、CSS、懒加载chunk、图标、字体、许可证及逐文件SHA-256清单；不包含源码映射、演示图片视频、node_modules或用户数据。

正常构建：

```sh
mvn -B -ntp -f labeling-service/pom.xml clean verify
python3 labeling-service/scripts/verify-editor-artifact.py labeling-service/target/tss-labeling-service-0.1.0-SNAPSHOT.jar
```

Maven在generate-resources阶段校验压缩包SHA-256；不匹配立即失败。校验通过后清理target内的旧编辑器资源并解压到target/generated-editor-resources，再自动加入JAR的BOOT-INF/classes/static/editor-assets。原src/main/resources/static/editor-assets缓存被显式排除，不参与打包，不需要手动复制文件。

资源测试校验固定版本、来源提交、逐文件大小与摘要、必需入口、懒加载chunk及CSS字体引用。部署前的Python检查再次核对最终可执行JAR，缺文件、额外旧文件或摘要错误都会阻止上传。

自动化已接入：

- labeling-editor-build.yml：功能分支及面向backend的PR执行干净构建与JAR检查，无部署步骤。
- labeling-backend-deploy.yml：原服务器部署流程在上传前增加JAR资源检查；是否合并该部署分支仍需团队审查。

### 维护时从上游源码重建

日常Maven使用已审查资源包；只有升级或重新制作资源时才执行上游构建。Linux/macOS或Git Bash中准备Git、Python3、Node22和Yarn1.22.22：

```sh
corepack prepare yarn@1.22.22 --activate
bash labeling-service/scripts/rebuild-editor-assets.sh
```

脚本在独立临时目录拉取固定提交，核对HEAD，使用上游锁文件安装及构建standalone editor，再重新生成资源包和校验属性。临时源码保留供审查；不会删除工作区。使用脚本不自动推送或部署。

package-editor-assets.py固定打包顺序、ZIP时间戳和权限元数据；上游重新编译产物仍需审查新摘要、许可证和浏览器行为，不能仅因版本号相同就接受。不要手工跳过或改写校验值掩盖损坏。

## 网关与前端

前端与/api/labeling/v1/editor-ui、editor-assets、assignments API应同源。正式环境使用HTTPS，核对后端识别代理HTTPS及Secure Cookie，允许同源窗口/iframe，避免SPA吞掉资源路由。launch查询中的ticket脱敏，禁止记录Token、Cookie和服务密码。

本地验证可将标注代理指向本机8088，身份代理指向已授权的统一身份服务；不能把本地验收地址当成团队可访问的服务。前端入口使用返回的editorUrl，不直接打开LS完整后台。旧项目直标前端功能分支已移除，本次只交付后端接口，正式前端按接入说明增加领取入口后再验收。

## 备份、回滚与故障

上线前备份业务库、原始图片、LS数据卷及受控配置。先关闭LABELING_EDITOR_ENABLED停止编辑器新会话/读写，再按团队流程恢复旧JAR，并验证Flyway对新增迁移的兼容性。保留新增表和数据卷，不通过删表回滚。

外部导入失败可能留下PENDING映射，需要核对LS任务、图片和规范后对账，不能盲目重建或直接改成READY。外部保存成功而平台落库失败时不显示成功，先GET业务草稿确认，SYNCING操作调用editor-sync读回恢复；不要盲目重复外部写入。权限/版本冲突及异常外部答案需要对账。

共同验收：同源入口、真实身份及本人领取、冻结规范、分类保存与恢复、N=2独立答案、整包提交后拒绝编辑、暂停/停用、错误提示、备份恢复。权限和数据契约由业务后端共同评审；未完成前只提交功能分支供审查。

## 本地隔离验收启动器

LocalEditorDemoTest是显式启用的测试工具，不进入生产JAR。需要EDITOR_LOCAL_DEMO=true、EDITOR_REAL_IDENTITY_URL、EDITOR_DEMO_LS_USERNAME、EDITOR_DEMO_LS_PASSWORD和独立LABELING_EDITOR_TICKET_SECRET；可设EDITOR_DEMO_DIR选择新的隔离目录。PostgreSQL使用其中assignment-postgres子目录，不打开旧postgres子目录；原始图片放images。启动器不自动创建项目、打包或领取，使用真实接口和本人登录操作。旧脚本和旧数据库不能作为本版已兼容的依据。
