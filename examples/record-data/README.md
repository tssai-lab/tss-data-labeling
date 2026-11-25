# 新数据类型的最小样例

样例由 `generate_samples.py` 生成到指定的本地目录，不把生成的二进制文件混入源码。

```powershell
python generate_samples.py C:\record-plugin-samples
```

生成 `structured-sample.zip`（CSV/XLSX/Parquet）和 `documents-sample.zip`（JSON/JSONL/XML/YAML），均包含中文、空值、零值以及需复核记录。

1. 创建“结构化数据标注”，导入 structured-sample.zip；创建“半结构化数据标注”，导入 documents-sample.zip。
2. 标签填写 `normal|正常`、`needs_review|需复核`、`person|人名`（每行一条）。保存规范。
3. 若需试验规则，使用 `{"type":"object","required":["id"]}`；字段映射留 `{}`。先不开启严格模式。
4. 发布时选择 1—2 条样本、每包 2 条、每样本 1 人；在任务广场领取。
5. 表格为整行选 normal，勾选 amount 单元格，选择 needs_review 后“应用到所选字段/节点”，再“完成并保存”。
6. 文档在树上定位 `/customer/name`，选择 person，选中姓名文本后添加实体；也可增加另一个实体并添加关系。为整条记录选 normal 后完成保存。
7. 提交完整任务包，在项目核查中确认，固定成果并导出。检查原始 id 保留为 `001`、字段标签与原路径一致、原始文档存在，manifest 文件摘要正确。

样例用于功能联调，业务标签含义仍由项目自己的规范决定。
