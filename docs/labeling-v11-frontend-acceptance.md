# 图片单标签分类前端交付与验收

本次功能分支：`feat/labeling-v11-frontend-gap`。只推送功能分支，等待用户本地验收并同意后才向 `frontend` 开 PR；不合并、不部署。

## 基线与范围

2026-11-09 重新 fetch 后核对：

- `origin/frontend`: `2e1560203329d8afe90179c3e01182dcc9b27fb6`
- `origin/backend`: `012157c09325fce8e39828e6aa16ac108b80385b`
- origin：`https://github.com/tssai-lab/tss-data-labeling.git`
- 开发检出：`D:\ASUS\Documents\codex\tssai\labeling-v11`。从最新 frontend 创建，未修改旧版验收目录 `D:\code\GitHub\tssai\frontend` 的现有工作。

审计依据为 backend 的 `docs/labeling-v1.1/{README,01-product,04-api,08-image-import-api,10-editor-integration}.md` 及 Project、Import、Task、Result、ActorAdmin、AssignmentEditor 的 Controller/Service。文档中早期“尚未实现”不覆盖当前代码证据；仓库存在实现也不代表部署可用。

沿用 Umi Max、React、TypeScript、Ant Design、统一登录 Bearer、幂等键、revision。保留训练平台、统一登录、内部 Demo 路由和资源；客户平台选择页仅列训练平台和正式标注入口，内部 Demo 仍可直接访问 `/#/label-projects`。

## 真实接口与页面

统一前缀 `/api/labeling/v1`。真实服务失败不会切换为 mock 成功，403 保留令牌并提示权限，401 才按统一规则清除令牌；展示服务错误码和 requestId。

| 范围 | 实际行为 |
| --- | --- |
| 领取工作台 | POST editor-sessions，同源 iframe；先监听再加载；校验 URL origin、准确会话路径、有效期、assignment/sample/actor/冻结 spec ID，校验可用 project ID。消息校验 origin、source、session 及业务 ID，旧会话卸载后不再处理。 |
| 保存与恢复 | LS 原生保存后必须 GET 本人草稿确认 revision。异常提供 editor-sync 后 GET，409 提供重新读取平台草稿；不把消息本身当保存成功。脏状态或保存进行中不允许直接整包提交；已提交但丢失回执的领取仍可用原幂等请求确认，切图/离开需握手，放弃需确认并等待 discarded。 |
| 原生表单 | 与 LS 互斥切换。必须先确认未决保存和未保存选择，再关闭当前编辑器；生产保留原生能力。已提交任务只读，不启动编辑会话。 |
| 用户管理 | `/#/labeling/admin/users`；入口及直接访问均 GET /me 核对 labelingAdmin。GET /admin/users 的 data 为数组，PATCH 专门允许 data:null。提交需原因及确认，禁止本人停用/撤权；不依赖训练角色。 |
| 规范 | 项目详情查看历史，从现有版本克隆；改名/上移保留标签 ID，新增生成独立 ID；创建后 GET 项目比对标签与 instructions。打包使用所选版本，既有预览/任务包保持原规范。 |
| 结果与进度 | 按真实提交游标加载，包/标注人筛选明确限已加载数据。已知计划通过真实 GET 确认 requiredCount；只在读完本次分页后显示该范围的提交人数，不声称全项目分母。 |
| 结果详情 | 使用提交的冻结 specVersionId 匹配真实规范；只从本机已知批次真实分页匹配 sourcePath，否则保留真实 ID。原图通过 owner 授权接口读取，拒绝时展示错误，不绕过权限。 |
| 本人历史 | 本人领取列表有已加载状态筛选及已知 submissionId 查询入口。没有本人提交历史/由 assignment 查 submissionId 的后端接口，不能恢复未知或跨设备回执。 |
| 批次 | 保留 ZIP 分片/续传、complete/retry、分页与授权图片；本机已知批次最多记录 100 个 ID，点击后读取真实详情，不宣称完整历史。 |

首期只提供 `labels[{id,name}]` 和规范整体 instructions，没有新增未经真实服务验证的标签说明/示例 JSON 字段，也不宣称服务端验证此类展示元数据。空选择表示未完成；业务“无目标”须作为合法稳定标签。已验证合成提交与导出中的 `none`，真实环境还须验证该业务类别。

长说明可展开，长文件名两行显示且可查看完整文本；双栏小屏改为单栏，图片/iframe 不撑开页面，表格横滚在容器内。工作台提供上一张/下一张、当前分类及 revision、切换表单和整包提交。导航有焦点和当前项，并提供“切换平台”。

## 缺失能力的开发适配器

`src/services/labelingGapMock.mjs` 不导入认证 HTTP 客户端，不拦截 fetch；真实 API 从不被它接管。只有独立 `/#/labeling/demo` 显式调用它。默认关闭，且配置和 adapter 双重拒绝生产启用。真实页面上的缺失操作显示“待后端接入”并禁用。

只有合成 `demo-*` 身份和资源可操作；真实 UUID/未知路径被拒绝。用户权限管理始终走真实接口，没有 mock 用户启停或授权。合成页面不要求真实账号，不请求登录、/me 或后端；切回真实平台后照常走统一登录。

种子为 `demo-project`、12 个图片元数据、10+2 分包、N=2。首包有 Alice/Bob 两份独立提交（正常 / 无目标），末包有一条未完成领取及一个未提交席位。没有合成原图文件；checksum 是明确标识的占位值，不冒充真实文件校验。页面内切换合成身份保留内存，重载页面或“重置种子”恢复。

| 模拟接口 | 请求 / 响应 / 限制 |
| --- | --- |
| POST /projects/{id}/exports | format=`native-json`、scope=`ALL_SUBMITTED`/`SELECTED_SUBMISSIONS`、submissionIds、includeOriginals=false；冻结提交清单，返回 exportId/status=QUEUED。`native-json` 是本地演示格式编码，真实服务需确认最终枚举。 |
| GET /exports/{id} | 手动查询推进 RUNNING，再 SUCCEEDED；可注入 FAILED，error.code/message。无后台队列、无持久化。 |
| GET /exports/{id}/download | 未完成409、过期410 EXPORT_EXPIRED、成功返回合成文件集合，由 UI 用 fflate 打包 ZIP。包含 results.json、mapping.csv、manifest.json、labels.json。映射 project/batch/sample/package/assignment/actor/submission/spec/sourcePath/checksum；两人结果不合并。60秒过期，可注入过期。 |
| POST /projects/{id}/pause,resume,archive | owner 或合成管理员，baseRevision；pause/archive 需1–500字 reason。暂停阻止工作访问，恢复至 OPEN；有有效领取不能归档，已归档不可恢复。 |
| POST /assignments/{id}/release | 本人、baseRevision、reason；只允许有效领取，变为 RELEASED。 |
| POST /admin/assignments/{id}/revoke | 合成管理员、baseRevision、reason；只允许有效领取，变为 REVOKED。释放/回收后工作访问被拒绝，历史提交保留。 |
| GET /admin/audit-events | 仅合成管理员，items/nextCursor=null；不是读取真实服务日志。 |
| GET /admin/projects, /admin/assignments | 仅合成管理员，q 为合成 JSON 字符串搜索；无真实管理查询、无原数据授权。 |
| GET /projects/{id}/imports | 仅合成 owner；READY/FAILED 完整合成批次列表。真实服务没有此 GET。 |

所有模拟 POST 校验幂等键，同键不同体409；状态操作校验 revision，失败保留错误码及合成 requestId。缺项目/提交/权限分别返回错误，不产生成功文件。演示故障注入属于本地测试控制，不是将来的 HTTP 契约字段。

替换点：真实后端提供并确认接口后，为缺失能力增加独立真实 service 方法、保持同样权限/幂等/revision/错误处理，再将真实页面禁用入口接到该方法。不要让 adapter 在真实请求 403/404/409/503 或网络错误后自动执行模拟写入。

## 本地启动（PowerShell）

先使用已有仓库 fetch 功能分支。不要覆盖现有改动：

```powershell
Set-Location -LiteralPath 'D:\code\GitHub\tssai\frontend' -ErrorAction Stop
$branch = 'feat/labeling-v11-frontend-gap'
git status --short --branch
git remote -v
# 确认 origin 指向 tssai-lab/tss-data-labeling 后继续。
git fetch origin "refs/heads/${branch}:refs/remotes/origin/${branch}"
if ($LASTEXITCODE -ne 0) { throw '拉取功能分支失败' }
```

工作区干净且首次检出：

```powershell
git switch --track "origin/$branch"
if ($LASTEXITCODE -ne 0) { throw '切换功能分支失败' }
git rev-parse HEAD
```

已有同名本地分支且工作区干净：

```powershell
git switch $branch
if ($LASTEXITCODE -ne 0) { throw '切换功能分支失败' }
git pull --ff-only origin $branch
if ($LASTEXITCODE -ne 0) { throw '无法快进，请用独立 worktree 验收' }
git rev-parse HEAD
```

有现有改动、无法快进或需保留旧版时，使用 detach worktree，不占用同名分支：

```powershell
$reviewDir = 'D:\code\GitHub\tssai\frontend-v11-review'
git worktree list
if (Test-Path -LiteralPath $reviewDir) { throw '验收目录已存在，请核对并复用已有 worktree 或改用空目录' }
git worktree add --detach "$reviewDir" "origin/$branch"
if ($LASTEXITCODE -ne 0) { throw '创建验收工作区失败' }
Set-Location -LiteralPath "$reviewDir" -ErrorAction Stop
git rev-parse HEAD
```

核对 HEAD 与本次交付 SHA 一致，使用 Node.js 20+（本次22.11.0）：

```powershell
npm ci
if ($LASTEXITCODE -ne 0) { throw '安装依赖失败' }
$env:DEV_API_TARGET = 'http://127.0.0.1:8080'
$env:DEV_LABELING_TARGET = 'http://127.0.0.1:8082'
Remove-Item Env:LABELING_GAP_MOCK -ErrorAction SilentlyContinue
npm run dev:local
```

入口：[平台选择](http://127.0.0.1:8001/#/platforms)、[标注平台](http://127.0.0.1:8001/#/labeling)。统一登录连接 DEV_API_TARGET；所有 `/api/labeling/`（含 editor-ui、editor-assets）连接 DEV_LABELING_TARGET。dev:local 不再覆盖调用者显式配置的两个代理目标。此处没有提供远程联调网关或账号：用户本次确认无隔离环境，不能声称真实链路已验收。

缺失能力的独立演示：停止当前开发实例后，在同一工作目录执行：

```powershell
npm run dev:labeling-demo
# 浏览器直接访问 http://127.0.0.1:8001/#/labeling/demo
# 不需要真实后端/账号；真实业务页面仍须真实登录与服务。
```

8001 被旧实例占用时，保留旧实例并用8002（默认关闭演示）：

```powershell
$env:HOST = '127.0.0.1'
$env:PORT = '8002'
Remove-Item Env:LABELING_GAP_MOCK -ErrorAction SilentlyContinue
npm run start:dev
# 如需在独立端口启用演示，先设 $env:LABELING_GAP_MOCK = 'true'，然后运行 start:dev。
```

## 网关与编辑器联调

开发代理保持 editor-ui/editor-assets 原路径，不做前缀剥离；Cookie domain 改为同源 host。预览网关 `scripts/serve-dist.cjs` 也先转发 `/api/labeling` 到 DEV_LABELING_TARGET，再匹配训练 `/api`。生产使用仓库现有部署工作流，需在真实同源网关配置等价规则，例如：

```nginx
location ^~ /api/labeling/ {
    proxy_pass http://labeling_service; # 不追加会剥离原始路径的 URI
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
}
```

实际域名、TLS、上游名沿用获准环境；不用此示例直接部署。需验证一次性 ticket 在60秒内跳转、第二次入口拒绝、HttpOnly/SameSite=Strict/会话路径 Cookie、生效期及 editor-assets；不要在代理 access log 记录 editor-ui launch 查询串。前端不打印、不持久化 editorUrl/ticket/token，也不向 iframe 发令牌。真实网关的日志配置及 Cookie 行为本次未验证。

## 验证证据与待联调

最终 `npm run tsc`、`npm test`（279/279）、`npm run build` 均通过；构建时显式设置 LABELING_GAP_MOCK=true，仍通过生产关闭检查。`validation.json`、`default-off-report.json`、`production-forced-flag-report.json` 保存结果。构建刷新的 Swagger UI 和 npm 安装刷新的 Husky 文件均已恢复，不纳入提交。

`docs/labeling-v11-evidence/browser-report.json` 及同目录 PNG：项目详情、任务广场、领取编辑、结果、管理与弹窗的1280/768/390截图，另有空态、403、409和合成演示。检查页面整体宽度、表格容器滚动、长文件名、长说明、宽图、分页与弹窗。浏览器使用本机 Chrome，HTTP 夹具仅属于测试脚本，**截图中的编辑器是合成受控页面，不是真实 Label Studio**。

`synthetic-export.zip` 是浏览器下载后打开检查的合成结果，包含两份独立提交、20条结果、正常/无目标两个合法标签；末包未完成列于 manifest。`proxy-report.json` 和 `dev-proxy-report.json` 分别记录预览及 Umi 开发代理通过本机合成 HTTP 上游的跳转/Cookie/assets 转发，不代表真实 LS 或联调网关通过。

可复核命令：

```powershell
npm run tsc
npm test
npm run build
# 浏览器测试依赖只安装到忽略的 .qa，不修改项目 package-lock：
npm install --prefix .qa --no-save --package-lock=false playwright
# 独立启动 dev:labeling-demo 后，在另一终端运行：
node scripts/qa/labeling-v11.browser.mjs
# 独立启动默认关闭的 dev:local 后：
node scripts/qa/labeling-v11-mode.browser.mjs
# build 产物存在时，用临时本机 HTTP 上游检查预览代理：
node scripts/qa/labeling-v11-proxy.mjs
# dev:local 或 dev:labeling-demo 在8001运行且本机8082空闲时，可检查开发代理：
node scripts/qa/labeling-v11-proxy.mjs --dev
```

不要同时在同一检出目录运行 Umi build 和 dev：二者会刷新相同的 Umi 生成配置；切换构建模式后重新启动开发服务器。

本次无可用隔离后端/账号，以下必须待真实联调，不记为通过：创建项目→规范读回→ZIP分片/续传/解析→默认10张保留末包→N=2发布→两人领取、LS独立保存与整包提交→需求方看两份结果；第三人超领、本人重复领取、越权原图、草稿竞争/刷新回读、整包幂等重放、暂停/回收迟到访问、普通用户直达管理、自停用/自撤权后端409、真实 /me403/401及 LS 禁用/白名单/票据过期/外部不可用。已有功能的客户端单元测试及本次 UI 夹具不替代这些验证。

完整批次历史、全项目分母、本人提交历史查询、管理员项目/领取查询、导出和生命周期接口仍依赖后端。缺失能力只在独立合成演示可用；没有浏览器可读真实审计接口。交付后请先本地验收，再确认允许创建 base=`frontend` 的 PR。
