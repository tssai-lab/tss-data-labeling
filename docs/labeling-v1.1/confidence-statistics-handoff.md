# AI 置信度统计前端交接

本次在项目结果统计和新建选择快照中增加置信度统计。沿用已有策略和候选选择规则，不生成模型分数，也不改变审核状态。接口字段定义见 [04-api.md 第 8 节](04-api.md#8-结果查询选择快照与分档)。

## 1. 读取实时统计

使用当前登录用户的 Token。`apiBase` 是标注业务 API 前缀，例如同源的 `/api/labeling/v1`；ID 均由现有项目、执行详情和置信度策略接口取得。

```javascript
async function readApi(url, token) {
  const response = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const body = await response.json();
  if (!response.ok) {
    throw new Error(body.error?.message || body.message || `请求失败：${response.status}`);
  }
  return body.data;
}

async function getConfidenceStatistics({
  apiBase = '/api/labeling/v1',
  token,
  projectId,
  executionId,
  policyId,
  band,
}) {
  const query = new URLSearchParams({ executionId });
  if (policyId) query.set('confidencePolicyId', policyId);
  if (band) query.set('band', band);
  const base = apiBase.replace(/\/$/, '');
  return readApi(
    `${base}/projects/${encodeURIComponent(projectId)}/result-statistics?${query}`,
    token,
  );
}

// 传入已有的 token、projectId、executionId、policyId。
// const stats = await getConfidenceStatistics({ token, projectId, executionId, policyId });
// const confidence = stats.confidenceStatistics;
// const highCount = confidence.bandCounts?.HIGH;
// const bars = confidence.histogram;
```

与列表联动时，两边必须使用同一份筛选。例如点击 HIGH 分档后，列表和统计都传 `executionId`、`confidencePolicyId`、`band=HIGH`，不要拿全执行统计作为筛选后的数量。分页游标和 limit 只传给列表接口。

## 2. 展示口径

- `aiResultCount` 是当前可明确展示的 AI 答案数；`scoredCount` 有实际分数；`unknownCount` 没有可信分数。人工、自评、owner 修正及集成投票不计作模型置信度。
- `unresolvedSampleCount` 是多候选待选择的样本数，不应展示随意挑出的某份候选的分数。用户从样本历史明确选择后，再刷新实时统计。
- `bandCounts=null` 表示没有指定策略。界面显示“未选择置信度策略”，不显示四档全零。指定策略时按既有 L、H 分档，不在浏览器重新定义阈值。
- 直方图始终有 10 档；前九档 `[lower,upper)`，最后一档 `[0.9,1.0]`。分数 0 和 1 都是有效值，不能用 `if (confidence)` 判断是否存在。
- 直方图合计等于 `scoredCount`；四档合计等于 `aiResultCount`。失败/无答案的样本由基础统计及执行详情展示，不混入 UNKNOWN。

## 3. 固定选择后的预览

```javascript
async function getSelectionStatistics({
  apiBase = '/api/labeling/v1', token, selectionId,
}) {
  const base = apiBase.replace(/\/$/, '');
  return readApi(
    `${base}/result-selections/${encodeURIComponent(selectionId)}/statistics`,
    token,
  );
}
```

创建选择后，最终范围预览使用上述快照统计及同一个 selectionId 的 items。排除项已从统计剔除；显式选择 resultId 时统计实际选中的答案。快照不会随后续审核变化。

升级前创建的快照可能缺少 `confidenceStatistics`。这表示旧版本未保存该项统计，界面应提示不可用，需要时创建新选择；不要用实时接口补成历史快照数据。

## 4. 本地验证

新增的集成测试使用临时 PostgreSQL、测试身份服务和明确的测试分数，调用真实上传、AI 输出冻结、策略、列表、统计与选择接口，不依赖真实模型 Worker 或共享数据库。

测试范围包括 0 和 1、全部十分位边界、L/H 边界、未知分数、失败/无答案、逐档列表分页数量、跨用户拒绝、缺失策略参数、排除项、显式多候选选择及快照冻结。

2026-11-22 兼容性修订基于 `backend` 提交 `6c10afa`，保留新增通用导出代码、成果版本删除、V26/V27 迁移及新的格式断言。新增 3 项集成测试通过；显式启用 `LABELING_NODE_E2E=true` 和 Python 测试后的完整 Maven verify 共 89 项，87 通过、2 按条件跳过、0 失败/错误。原生工作流 9 项、编辑器适配器 6 项及 Node HTTP 交接脚本均实际执行通过。跳过项仅为未配置真实能耗数据集参数的两项测试，不计为通过。

针对性测试命令（已配置 Maven、JDK 和 Python 适配器环境时，在 labeling-service 目录执行）：

```powershell
mvn '-Dtest=ImportIntegrationTest#confidenceStatistics*' test
```

完整回归显式启用 Python 相关测试，例如 `mvn '-Dls.test.python=Python可执行文件的绝对路径' verify`。本地测试通过不代表该修改已合并、部署或完成服务器前端联调；线上验收需等 PR 合入及部署完成后执行。
