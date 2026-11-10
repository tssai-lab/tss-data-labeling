# 本次模板与能耗对齐证据

代码父提交：`1e05cccc41b7e9cdafe40980e1125969eed3b023`。需求固定于backend `fafefcaebd7cbb8fd4dec0cbea7a3576bb9703e2`，13份文档摘要见requirement-sources.json。最终检查见validation.json。

- browser-report.json和31张分类截图：合成HTTP夹具及独立工作流回归，未调用真实账号/服务。
- energy-browser-report.json和6张能耗截图：10条合成站点日；零值、缺测、坐标、刷新恢复、完整交包、owner、成果、质检预览及导航。
- default-off-report.json、production-forced-flag-report.json：两个演示入口关闭，真实403保留令牌、不回退模拟写入。
- synthetic-energy-confirmed.zip：10条已明确确认的合成成果，完整JSONL、辅助CSV、manifest及文件SHA-256。不是受限验收数据或真实标注。
- confirmed-classification-export.zip：固定范围1条已确认合成分类结果，不是全项目完成。
- archive-validation.json：归档结构、数量、SHA-256、坐标及来源核对。

不包含原始能耗包、真实金标准、真实人员双标或模型准确率。文档、Mock和浏览器夹具不能替代真实联调与专家验收。
