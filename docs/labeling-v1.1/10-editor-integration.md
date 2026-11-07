# 图片分类编辑器接入交付（D汤立娜）

## 实现范围

复用现有平台身份、图片导入及领取接口，新增Label Studio图片分类会话、结果读回及平台草稿保存。
不新增任务广场、领取页面或另一套平台登录。普通标注员不取得Label Studio后台账号、Token或密码。

当前实现使用官方1.23.2源码构建的编辑组件，受控页面由标注后端提供；结果由后端写入真实Label Studio服务、读回校验，再保存平台数据库。
这是针对共享社区版隔离不足实现的受控接入方式；共享后台本身的权限没有被改变，也不向标注员开放它。

## 前端需要对接的接口

统一前缀`/api/labeling/v1`，沿用平台Bearer登录凭据与`{data,requestId}`响应。

### 1. 打开编辑器

`POST /assignments/{assignmentId}/editor-sessions`

请求头：Authorization、Content-Type: application/json、Idempotency-Key。

```json
{"sampleId":"实际图片UUID"}
```

响应data包括：

```json
{
  "editorSessionId":"会话UUID",
  "assignmentId":"实际领取UUID",
  "sampleId":"实际图片UUID",
  "actorId":"后端确定的实际平台标注员UUID",
  "mode":"EMBEDDED_LABEL_STUDIO",
  "editorUrl":"/api/labeling/v1/editor-ui/会话UUID/launch?ticket=一次性随机值",
  "launchExpiresAt":"ISO8601时间（入口60秒）",
  "expiresAt":"ISO8601时间（会话30分钟）",
  "draft":{"revision":0,"explicitDone":false,"result":[],"resultFormat":"label-studio-json","savedAt":null},
  "config":"由任务规范生成的XML"
}
```

前端打开返回的同源editorUrl即可。无需另行登录Label Studio，也不需要在URL中附加平台Bearer Token。
同一幂等键、同一请求重试返回原会话；原入口已使用或过期时，用新幂等键重新请求，而不是重复使用旧ticket。

浏览器弹窗应在用户点击时先创建，接口返回后再导航，避免异步window.open被浏览器拦截。
可使用同页导航。入口URL是一项短时授权，不写分析日志、不外传；网关应关闭launch路由的查询参数日志或脱敏ticket。

### 2. 编辑器内保存

受控页面自带“保存分类并同步到平台”按钮，C不必重写分类编辑器。一次操作包含：

```text
校验会话与有效领取 → 校验类别/版本 → 写入LS → 读回任务及服务作者
→ 校验读回结果 → 保存平台草稿及映射 → 返回revision
```

浏览器只调用绑定会话的image/context/result端点，不能自行指定其他标注员、图片或外部结果ID。
页面显示平台保存确认，失败不冒充成功。重新加载会从平台数据库恢复。

使用popup时，页面保存成功可向同源opener发送：

```json
{"type":"labeling:saved","assignmentId":"...","sampleId":"...","actorId":"...","revision":1}
```

C接收时校验event.origin、event.source及当前任务/图片，再查询平台接口确认；不能只凭postMessage当作保存证据。
若启用COOP/noopener等导致opener为空，可改为用户返回后查询，消息不影响服务端保存。

### 3. 同步确认或重试查询

`POST /assignments/{assignmentId}/editor-sync`

请求头与会话接口相同，body同样为`{sampleId}`。后端读回映射的结果并核对作者、任务及摘要，返回平台草稿版本。
当前受控保存已经完成回传；本接口用于再次确认。未有成功保存返回503，不从共享后台随意挑选一份答案。
同一幂等键返回第一次成功响应；新一次查询使用新键。

### 4. 读取本人结果

`GET /assignments/{assignmentId}/drafts/{sampleId}`，带Bearer凭据。

data字段为revision、explicitDone、resultFormat、result、savedAt。未保存时revision=0、result=[]。
刷新平台页面后使用此接口显示本人分类；其他标注员访问返回404。
也可重新请求editor-sessions，从draft恢复编辑。

### 5. 保存接口（统一逻辑）

`PUT /assignments/{assignmentId}/drafts/{sampleId}`：在04-api既有字段基础上增加必填editorSessionId，固定使用同一保存及LS读回路径，避免两种结果来源竞争覆盖。

```json
{
  "editorSessionId":"有效会话UUID",
  "baseRevision":0,
  "explicitDone":true,
  "resultFormat":"label-studio-json",
  "result":[{"from_name":"category","to_name":"image","type":"choices","value":{"choices":["破损"]}}]
}
```

通常由受控编辑页面自动调用，C不需要手工构造。后端重建规范化结果，不信任传入的作者或外部ID。
旧版本返回409；保存响应丢失后，相同baseRevision与内容可重试而不产生新版本。
当前仅支持图片单选分类，explicitDone=true且恰好一个合法类别；不包含“无目标”模板或正式整包提交。

## 用户、任务与结果归属

- 平台actorId来自已验证身份；assignment必须属于本人，sample必须属于该包。
- 分类规范使用发布时绑定的specVersionId，不使用任意“最新标签”。
- annotation_draft由assignment+sample唯一标识；assignment关联实际actor。
- editor_mapping记录平台actor/spec、外部任务/结果、原图摘要及结果摘要。
- LS的completed_by是服务器技术账号，单独记录external_author_id并校验；它不是实际平台标注员。实际标注员以平台验证并保存的actor/assignment映射为准。
- UI组件内部的数值用户ID仅为渲染占位，不用于后端授权、归属或审计。
- 两个标注员对应不同外部任务和不同平台草稿，不能通过改ID访问对方结果。

## 权限与错误

- 项目须OPEN、任务包须OPEN、领取须CLAIMED或IN_PROGRESS、账号须ENABLED。
- 每次图片、页面上下文和保存请求均复核当前状态；回收、暂停、禁用后旧页面不可继续读写。
- launch为60秒一次性票据，后续使用HttpOnly/SameSite=Strict、会话路径限定的随机Cookie；数据库只存票据和Cookie摘要。
- 正式环境须HTTPS。Secure Cookie依赖后端正确识别可信反向代理后的HTTPS，部署时须核对forward-header策略；不要直接暴露HTTP服务。
- 401：未登录/会话失效；404：他人或未开放资源；409：版本冲突/领取结束/暂停/待对账；422：非法分类；503：编辑器不可用。
- 外部项目或任务创建前持久化PENDING，异常后不盲目重复创建；由管理员核对external_project_id、实际任务及规范后修复。PENDING不是成功映射。

## 数据库迁移与配置

新增V5__create_editor_drafts.sql：annotation_draft、editor_mapping、editor_session，组合外键限定领取和包成员，包含应用账号授权。
合并前检查团队是否已有V5，必要时按团队迁移规范调整版本；不要手工在共享库重复建表。

```text
LABELING_ASSIGNMENT_EDITOR_ENABLED=true
LABELING_EDITOR_PROJECTS=允许联调的项目UUID,其他项目UUID
LABELING_EDITOR_BASE_URL=http://127.0.0.1:8085
LABELING_EDITOR_USERNAME=Label Studio服务账号
LABELING_EDITOR_PASSWORD=由受控环境提供
```

默认关闭，默认白名单为空。`*`用于隔离测试；真实环境优先明确列出项目。地址/凭据只放服务器受控配置。
保留Label Studio原有独立数据卷。该适配不需要新建第二套平台角色系统。

## 构建与部署

JDK17、Maven；编辑组件须先从官方1.23.2源码构建并复制到static/editor-assets，生成目录不提交源码仓库，构建交付JAR已包含资源。
具体可复现步骤、资源摘要与JAR校验值见本次交付README和manifest。

通过团队PR与原自动部署流程合并到backend；本次不替换服务器原平台JAR、不执行共享库迁移。曾在本地前端增加“我的已领取任务”及多图联调入口，复用现有登录与领取接口；验收后已按用户要求全部撤回，未推送。当前交接状态见11-editor-handoff.md。
上线前备份业务库及LS数据卷，评审新迁移，验证HTTPS、同源路径转发、功能开关和双账号流程。
所有/api/labeling/v1/editor-ui及editor-assets路径应同源转发标注后端，不能被SPA页面吞掉。

关闭功能开关可阻止后续会话和读写。回退旧JAR前保留新增表和记录，不删表；需验证旧版Flyway对未来迁移的兼容性。LS中的任务和结果不自动删除。

## 验收边界

交付测试区分：模拟LS接口测试、真实LS服务测试、真实编辑器浏览器操作。测试身份与平台数据库均为隔离环境；真实图片是从用户已上传批次复制后，经原导入接口代码重新导入。
最初隔离测试证明适配闭环。随后已通过真实统一身份与正式前端的本地联调，验证保存、更新、刷新和读回；业务数据仍为本机独立数据，不代表线上原项目已接好按钮。团队正式部署与线上验收仍待合并后完成。
