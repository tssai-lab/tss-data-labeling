# 标注工作台调整与接口核对

## 界面审查

产品为需求方和标注人员提供数据导入、任务发布、样本审核和成果交付。界面保持原有深蓝侧栏、浅色画布和蓝色操作色；重点让样本内容、选择状态和审核结论更容易辨认。此次按用户截图优先处理操作分散、编号代替文件名和预览无法收起的问题，保留现有任务流程和真实数据。

使用的 [unslop](https://github.com/heydamir/unslop) skill 由 Damir Sokolovsky（[@heydamir](https://x.com/heydamir)）创建，采用 CC BY 4.0。因本地浏览器访问受安全策略限制，此次对比依据用户截图与源代码，未完成浏览器截图验收。

## 后端接口

核对基线：backend 提交 `094cdb735481bb9678177d3f04d4e75cbc3071a5`，包括 `docs/labeling-v1.1/04-api.md`、`TaskController`、`TaskService` 和 `NativeTaskService`。这是源码契约，不代表联调服务器已部署同一版本。

- `GET /projects/{id}/input-samples` 返回 `sampleId`、`sourcePath` 和 `metadata`；审核列表按 sampleId 关联项目样本，优先显示文件名，缺失时保留编号。
- `POST /projects/{id}/package-plans` 支持 `reopenAccepted`；前端明确传入当前勾选值，避免依赖兼容默认值。
- `POST /projects/{id}/prelabel-runs` 同样支持 `reopenAccepted`；选择 AI 标注时也会传递该值。发布计划的同一请求失败后重试使用相同幂等键；更改计划内容或完成发布后生成新键。
- `GET /submissions/{id}` 和 `/review` 继续作为审核内容与结论来源，切换样本和刷新保留编辑保护。
- 发布计划的 `packageSampleCounts` 只有各包数量。当前 `planView` 的 `packages` 在发布前为空，不返回各包 sampleId，故前端无法可靠提供预览中的按包筛选；不能把客户端推算的顺序当作服务器确认的成员。

后端建议为计划预览提供冻结包成员，例如 `packages: [{sequenceNo, sampleCount, sampleIds}]` 或可分页的计划成员接口，并保证成员与最终发布一致。前端现显示这一限制；接口上线后再接入筛选。

此次核对和调整上述相关接口，并非全部后端接口均已升级或在联调服务器验收。当前原生成果页面仍使用文档 9.3 的兼容接口；文档 9.2 的精确结果选择、审核版本冲突检查和固定选择范围需要进一步核对并接入对应业务流程。结果统计、聚合、插件等功能也需要逐项对照服务端部署版本验证。不得将演示页面或兼容实现当作已完成全部新接口。

## 验证范围

类型检查和全部 412 项离线测试通过。项目工作区测试已更新为真实 AI 执行接口流程，项目列表测试已补齐日期组件依赖并适配名称筛选和项目操作。页面视觉和联调服务器写入由用户手工验收。
