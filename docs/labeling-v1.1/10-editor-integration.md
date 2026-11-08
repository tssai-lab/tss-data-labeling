# 图片分类编辑器接入说明

## 范围和权限

本阶段使用已有项目、已导入图片和平台登录身份进行图片单选分类，不要求发布或领取任务。当前沿用平台既有权限：仅项目创建者可标注本人项目。项目须为DRAFT或OPEN，图片导入须READY，项目须存在分类规范。

暂停、归档、停用账号、他人项目和跨项目图片均拒绝。协作授权、任务领取、整包提交、审核、导出及框选不属于本次新增功能。已有业务任务接口未改动。

## 实际嵌入与保存链路

```text
平台项目图片页面
→ ProjectEditorEntry按钮调用平台会话接口
→ 新窗口打开平台后端提供的受控HTML
→ HTML加载Label Studio官方编辑组件JS/CSS
→ bridge.js传入图片地址与分类XML
→ 用户保存，平台后端校验身份、图片、类别和revision
→ 调用LS API写入并读回核对
→ 保存平台个人结果
→ 通知原窗口，再查询平台结果确认显示
```

当前使用新窗口承载组件，不是打开LS完整管理后台，也不是平台页面直接请求LS原生API。LS服务账号只在后端；实际标注员通过平台actor关联，不能把LS技术账号completed_by当成真实标注员。

## 接口

前缀`/api/labeling/v1`；使用既有`Authorization: Bearer <平台Token>`。成功响应`{data,requestId}`，错误响应`{error:{code,message},requestId}`。用户ID由后端验证，不接受前端指定保存人。

| 方法与路径 | 用途 |
|---|---|
| POST /projects/{projectId}/editor-sessions | 打开当前图片的编辑会话 |
| GET /projects/{projectId}/samples/{sampleId}/annotation | 读取当前规范下本人分类结果 |
| PUT /projects/{projectId}/samples/{sampleId}/annotation | 保存，通常由编辑器自动调用 |

创建会话另带`Content-Type: application/json`及`Idempotency-Key`：

```json
{"sampleId":"已有图片UUID"}
```

响应data含editorSessionId、projectId、sampleId、actorId、specVersionId、mode、editorUrl、launchExpiresAt、expiresAt、config、draft。mode为PROJECT_IMAGE_CLASSIFICATION。draft及GET结果含revision、explicitDone、resultFormat、result、savedAt，以及项目/图片/用户/规范标识。

editorUrl为平台同源`/api/labeling/v1/project-editor-ui/`入口：60秒一次性，后续会话30分钟。同一幂等键在入口未使用且有效时可重试取得同一会话；已使用、过期或密钥轮换后返回409，使用新幂等键重新申请。Token不放进URL，ticket不记录日志。

未保存时revision=0、result=[]，不得解释为“正常”。每份结果由project＋sample＋actor＋specVersion确定。规范更新使旧会话失效，新规范结果独立保存，不覆盖旧记录。

直接保存正文：

```json
{"editorSessionId":"有效会话UUID","baseRevision":0,"result":[{"from_name":"category","to_name":"image","type":"choices","value":{"choices":["正常"]}}]}
```

仅接受一个合法类别。成功返回新revision；相同baseRevision和相同内容可在响应丢失后重试，不重复增加版本；旧版本不同内容返回409。类别非法422、服务不可用503；失败不得冒充成功。

## 前端调用

ProjectEditorEntry.tsx负责打开窗口、核对同源及全部上下文ID、查询本人结果。index.tsx只接入入口和分类标签展示。正式布局可调整，不重写保存逻辑。

编辑器通过postMessage发送labeling:ready、labeling:saved、labeling:save-failed；响应含editorSessionId/projectId/sampleId/actorId/specVersionId。接收时检查event.origin、event.source和全部ID，保存通知后再GET annotation确认结果，不能只凭通知当成保存证据。

可发送labeling:save、labeling:request-state、labeling:discard，带editorSessionId和唯一requestId；对应返回saved/save-failed、state、discarded。state含ready/saving/dirty。切图前处理未保存修改，保存失败或仍有新修改时不自动切图。

## 调用示例

Node18+，私有环境配置LABELING_API_BASE（含/api/labeling/v1）、LABELING_TOKEN、LABELING_PROJECT_ID、LABELING_SAMPLE_ID：

```text
node labeling-service/examples/project-editor-integration.mjs open
node labeling-service/examples/project-editor-integration.mjs read
```

open默认隐藏一次性入口，私下演示可设LABELING_SHOW_ENTRY=true，勿分享输出。接口参数使用调用环境实际ID，不照抄测试ID。

## 代码范围

- ProjectEditorController/Service/UiController：项目模式接口、授权、同步和受控页面。
- ClassificationTemplate、ClassificationResultValidator：模板生成和独立的分类内容校验，不依赖领取服务。
- LabelStudioClient、EditorAssets、editor-ui：LS通信、官方组件资源和交互桥接。
- V6：项目个人结果、外部映射及会话；V5保留为已执行过的迁移历史，不能随意改写校验值或删除。旧领取编辑器Java代码及其演示已移除，既有业务TaskService不变。

部署配置见[部署说明](11-editor-deployment.md)，回归结果及限制见[验证与评审记录](12-editor-verification.md)。
