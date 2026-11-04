# 模块二测试执行细节手册

## 1. 文档用途

本文档是 `module2-test-plan.md` 的执行手册，解决“具体怎么跑”的问题。

覆盖内容：

- 如何准备测试账号和 token。
- 如何准备模型 zip。
- 如何准备 CV/NLP 数据集。
- 如何自己生成最小测试数据。
- 如何寻找真实公开数据集。
- 如何执行上传、查询、训练、越权、安全回归测试。
- 如何检查数据库和 MinIO。
- 如何记录测试结果。

推荐先用本文档中的“小型自造数据集”跑通后端链路，再使用真实数据集做演示或验收补充。

## 2. 前置条件

### 2.1 后端服务已启动

本地：

```powershell
cd E:\resource\TSSAIPlatform\backend
.\mvnw.cmd clean package -DskipTests
java -jar target\tss-backend-1.0.0.jar
```

服务器：

```bash
cd /opt/tss-platform/backend
pkill -f tss-backend-1.0.0.jar
nohup java -jar target/tss-backend-1.0.0.jar > backend.log 2>&1 &
tail -f backend.log
```

成功标志：

```text
Tomcat started on port 8080
Started TssPlatformApplication
```

### 2.2 PostgreSQL 和 MinIO 已启动

服务器：

```bash
cd /opt/tss-platform
docker compose -f compose.yml ps
```

健康检查：

```bash
curl http://127.0.0.1:8080/api/files/health
```

预期：

```json
{"success":true,"data":{"minio":"ok"},"errorMessage":null}
```

### 2.3 推荐工具

| 工具 | 用途 | 是否必须 |
| --- | --- | --- |
| PowerShell | Windows 本地执行命令 | 是 |
| curl.exe | 调接口 | 是 |
| PostgreSQL psql | 查数据库 | 建议 |
| MinIO Console 或 mc | 查对象路径 | 可选 |
| jq | 解析 JSON | 可选 |
| Postman / Apifox | 接口测试 | 可选 |

Windows 下建议使用：

```powershell
curl.exe
```

不要直接用 `curl`，因为 PowerShell 里 `curl` 可能是 `Invoke-WebRequest` 的别名。

## 3. 目录准备

在项目根目录创建测试文件目录：

```powershell
cd E:\resource\TSSAIPlatform
New-Item -ItemType Directory -Force test-files
New-Item -ItemType Directory -Force test-files\model-cv
New-Item -ItemType Directory -Force test-files\model-nlp
New-Item -ItemType Directory -Force test-files\cv-valid\cat
New-Item -ItemType Directory -Force test-files\cv-valid\dog
New-Item -ItemType Directory -Force test-files\cv-no-image
New-Item -ItemType Directory -Force test-files\cv-with-bad-file
New-Item -ItemType Directory -Force test-files\nlp-valid
New-Item -ItemType Directory -Force test-files\nlp-invalid
New-Item -ItemType Directory -Force test-files\images
```

后续所有测试文件默认放在：

```text
E:\resource\TSSAIPlatform\test-files
```

## 4. 生成最小模型测试包

模型上传只要求 `.zip`，并且现在会校验真实 zip 结构。

### 4.1 生成 CV 模型 zip

```powershell
Set-Content -Path test-files\model-cv\train.py -Encoding UTF8 -Value @'
print("mock cv training")
'@

Set-Content -Path test-files\model-cv\config.yaml -Encoding UTF8 -Value @'
task: CV
epochs: 1
batch_size: 2
'@

Compress-Archive -Path test-files\model-cv\* -DestinationPath test-files\model-cv.zip -Force
```

### 4.2 生成 NLP 模型 zip

```powershell
Set-Content -Path test-files\model-nlp\train.py -Encoding UTF8 -Value @'
print("mock nlp training")
'@

Set-Content -Path test-files\model-nlp\config.json -Encoding UTF8 -Value '{"task":"NLP","epochs":1}'

Compress-Archive -Path test-files\model-nlp\* -DestinationPath test-files\model-nlp.zip -Force
```

### 4.3 生成非法模型文件

```powershell
Set-Content -Path test-files\not-zip.pt -Encoding UTF8 -Value "not a zip model"
Set-Content -Path test-files\fake-model.zip -Encoding UTF8 -Value "this file name is zip but content is not zip"
```

预期：

| 文件 | 用途 |
| --- | --- |
| `model-cv.zip` | CV 模型上传成功用 |
| `model-nlp.zip` | NLP 模型上传成功用 |
| `not-zip.pt` | 初始化时失败 |
| `fake-model.zip` | complete 时 zip 结构校验失败 |

## 5. 生成最小 CV 数据集

后端 CV 数据集规则：

```text
CV zip / folder 必须至少包含一个图片文件
图片后缀支持 jpg、jpeg、png、bmp、gif、webp、tif、tiff
annotationFormat 默认为 NONE；NONE / FOLDER_CLASSIFICATION / MASK 只允许图片
CSV / YOLO / COCO / VOC / LABELME / OTHER 可按白名单携带标注文件
```

### 5.1 生成测试图片

用一个最小 PNG 的 base64 生成图片文件：

```powershell
$pngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
[IO.File]::WriteAllBytes("test-files\images\a.png", [Convert]::FromBase64String($pngBase64))
[IO.File]::WriteAllBytes("test-files\images\b.png", [Convert]::FromBase64String($pngBase64))
[IO.File]::WriteAllBytes("test-files\cv-valid\cat\a.png", [Convert]::FromBase64String($pngBase64))
[IO.File]::WriteAllBytes("test-files\cv-valid\dog\b.png", [Convert]::FromBase64String($pngBase64))
```

### 5.2 生成合法 CV zip

```powershell
Compress-Archive -Path test-files\cv-valid\* -DestinationPath test-files\cv-valid.zip -Force
```

### 5.3 生成没有图片的 CV zip

```powershell
Set-Content -Path test-files\cv-no-image\readme.txt -Encoding UTF8 -Value "no image here"
Compress-Archive -Path test-files\cv-no-image\* -DestinationPath test-files\cv-no-image.zip -Force
```

### 5.4 生成混入非法文件的 CV zip

```powershell
Copy-Item test-files\images\a.png test-files\cv-with-bad-file\a.png -Force
Set-Content -Path test-files\cv-with-bad-file\label.txt -Encoding UTF8 -Value "bad text in cv zip"
Compress-Archive -Path test-files\cv-with-bad-file\* -DestinationPath test-files\cv-with-bad-file.zip -Force
```

预期：

| 文件 | 预期 |
| --- | --- |
| `cv-valid.zip` | 上传成功 |
| `cv-no-image.zip` | complete 失败 |
| `cv-with-bad-file.zip` | complete 失败 |

## 6. 生成最小 NLP 数据集

后端 NLP 数据集规则：

```text
支持 .txt、.json、.jsonl、.csv、.xlsx、.xls、.pdf、.docx、.xml
也支持 zip
NLP zip 中只能包含 .txt、.json、.jsonl、.csv、.xlsx、.xls、.pdf、.docx、.xml
```

### 6.1 生成单文件 NLP 数据

```powershell
Set-Content -Path test-files\nlp-valid.txt -Encoding UTF8 -Value @'
hello world
this is a tiny nlp dataset
'@

Set-Content -Path test-files\nlp-valid.json -Encoding UTF8 -Value @'
[
  {"text":"hello world","label":"greeting"},
  {"text":"goodbye","label":"farewell"}
]
'@

Set-Content -Path test-files\nlp-valid.jsonl -Encoding UTF8 -Value @'
{"text":"hello world","label":"greeting"}
{"text":"goodbye","label":"farewell"}
'@
```

### 6.2 生成合法 NLP zip

```powershell
Copy-Item test-files\nlp-valid.txt test-files\nlp-valid\data.txt -Force
Copy-Item test-files\nlp-valid.json test-files\nlp-valid\data.json -Force
Copy-Item test-files\nlp-valid.jsonl test-files\nlp-valid\data.jsonl -Force
Compress-Archive -Path test-files\nlp-valid\* -DestinationPath test-files\nlp-valid.zip -Force
```

### 6.3 生成非法 NLP zip

```powershell
Copy-Item test-files\images\a.png test-files\nlp-invalid\a.png -Force
Set-Content -Path test-files\nlp-invalid\run.exe -Encoding UTF8 -Value "fake exe"
Compress-Archive -Path test-files\nlp-invalid\* -DestinationPath test-files\nlp-invalid.zip -Force
```

预期：

| 文件 | 预期 |
| --- | --- |
| `nlp-valid.txt` | 上传成功 |
| `nlp-valid.json` | 上传成功 |
| `nlp-valid.jsonl` | 上传成功 |
| `nlp-valid.zip` | 上传成功 |
| `nlp-invalid.zip` | complete 失败 |

## 7. 如何寻找真实数据集

### 7.1 推荐优先级

测试阶段推荐顺序：

1. 自造小数据集，最快，最可控。
2. 课程/项目组已有数据集，最贴近验收。
3. 公开小型数据集，适合演示。
4. 大型公开数据集，适合后续性能测试，不建议一开始用。

### 7.2 常用公开数据集来源

| 来源 | 适合类型 | 地址 |
| --- | --- | --- |
| Hugging Face Datasets | NLP、CV、多模态 | `https://huggingface.co/datasets` |
| Kaggle Datasets | CV、NLP、表格、竞赛数据 | `https://www.kaggle.com/datasets` |
| UCI Machine Learning Repository | 小型经典数据集 | `https://archive.ics.uci.edu/datasets` |
| TensorFlow Datasets Catalog | CV、NLP、音频等 | `https://www.tensorflow.org/datasets/catalog/overview` |

### 7.3 选择数据集的标准

找数据集时优先选：

```text
体积小
格式明确
许可证允许学习/测试使用
不含敏感个人信息
下载后能转成当前后端支持格式
```

CV 数据集最好满足：

```text
图片按目录分类
例如 cat/a.png, dog/b.png
总大小先控制在 100MB 以下
图片格式为 jpg/png/jpeg
```

NLP 数据集最好满足：

```text
txt/json/jsonl/csv/xlsx/xls/pdf/docx/xml 格式
每行一条文本或 JSON
编码为 UTF-8
总大小先控制在 100MB 以下
```

### 7.4 公开数据集下载后的整理方式

CV 数据集整理成：

```text
cv-real/
|-- class_a/
|   |-- 001.jpg
|   `-- 002.jpg
`-- class_b/
    |-- 001.jpg
    `-- 002.jpg
```

压缩：

```powershell
Compress-Archive -Path cv-real\* -DestinationPath cv-real.zip -Force
```

NLP 数据集整理成：

```text
nlp-real.jsonl
```

每行：

```json
{"text":"示例文本","label":"类别"}
```

或整理成 zip：

```text
nlp-real/
|-- train.jsonl
|-- dev.jsonl
`-- test.jsonl
```

压缩：

```powershell
Compress-Archive -Path nlp-real\* -DestinationPath nlp-real.zip -Force
```

### 7.5 不建议直接使用的数据

不建议用于当前后端功能测试：

```text
超过数 GB 的大数据集
必须登录复杂授权的网站数据
格式复杂需要额外解析的数据
包含个人隐私的数据
压缩包里混杂脚本、可执行文件、隐藏文件的数据
```

当前后端会按 `annotationFormat` 拒绝 CV zip 中不匹配的标注文件，也会拒绝 NLP zip 中不在格式白名单内的文件。

## 8. 获取 token

### 8.1 登录用户 A

```powershell
$BASE = "http://127.0.0.1:8080"

$loginA = curl.exe -s -X POST "$BASE/api/user/login" `
  -H "Content-Type: application/json" `
  -d '{"type":"account","username":"user_a","password":"test123"}'

$loginA
```

手工复制响应里的：

```text
data.token
data.userId
```

设置变量：

```powershell
$TOKEN_A = "替换为用户A token"
$USER_A_ID = "替换为用户A id"
```

### 8.2 登录用户 B 和管理员

```powershell
$loginB = curl.exe -s -X POST "$BASE/api/user/login" `
  -H "Content-Type: application/json" `
  -d '{"type":"account","username":"user_b","password":"test123"}'

$loginAdmin = curl.exe -s -X POST "$BASE/api/user/login" `
  -H "Content-Type: application/json" `
  -d '{"type":"account","username":"admin01","password":"test123"}'
```

设置：

```powershell
$TOKEN_B = "替换为用户B token"
$USER_B_ID = "替换为用户B id"
$TOKEN_ADMIN = "替换为管理员 token"
```

### 8.3 验证 token

```powershell
curl.exe -s "$BASE/api/user/current-user" `
  -H "Authorization: Bearer $TOKEN_A"
```

预期能返回当前用户信息。

## 9. 一片上传的通用流程

后端默认分片大小：

```text
5MiB
```

如果文件小于 5MiB：

```text
totalChunks = 1
partIndex = 0
```

测试阶段建议先全部使用小文件，流程更简单。

### 9.1 测试数据上传后端快捷指令

如果你只是想先把测试数据传到后端，确认模块二上传链路可用，可以直接执行本小节。

前提：

```text
1. 已按第 4、5、6 节生成测试文件。
2. 已按第 8 节设置 $BASE、$TOKEN_A。
3. 测试文件都小于 5MiB，因此只上传 partIndex=0。
```

如果你是 SSH 端口转发访问服务器后端，通常保持：

```powershell
$BASE = "http://127.0.0.1:8080"
```

如果服务器已经通过 Nginx 暴露 `/api/`，可以改成：

```powershell
$BASE = "http://服务器IP"
```

上传 CV 模型：

```powershell
$modelFile = "E:\resource\TSSAIPlatform\test-files\model-cv.zip"
$modelSize = (Get-Item $modelFile).Length

$modelInitBody = @{
  fileName = "model-cv.zip"
  fileSize = $modelSize
  fileFingerprint = "model-cv-v1"
} | ConvertTo-Json -Compress

$modelInit = curl.exe -s -X POST "$BASE/api/model/upload/init" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $modelInitBody

$MODEL_UPLOAD_ID = (($modelInit | ConvertFrom-Json).data.uploadId)

curl.exe -s -X POST "$BASE/api/model/upload/chunk" `
  -H "Authorization: Bearer $TOKEN_A" `
  -F "uploadId=$MODEL_UPLOAD_ID" `
  -F "partIndex=0" `
  -F "file=@$modelFile"

$modelCompleteBody = @{
  uploadId = $MODEL_UPLOAD_ID
  modelName = "resnet-test"
  version = "v1"
  type = "CV"
  remark = "CV 模型上传测试"
} | ConvertTo-Json -Compress

$modelComplete = curl.exe -s -X POST "$BASE/api/model/upload/complete" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $modelCompleteBody

$modelComplete
$MODEL_VERSION_ID_A = (($modelComplete | ConvertFrom-Json).data.id)
$MODEL_ASSET_ID_A = (($modelComplete | ConvertFrom-Json).data.assetId)
```

上传 CV 数据集 zip：

```powershell
$cvFile = "E:\resource\TSSAIPlatform\test-files\cv-valid.zip"
$cvSize = (Get-Item $cvFile).Length

$cvInitBody = @{
  fileName = "cv-valid.zip"
  fileSize = $cvSize
  fileFingerprint = "cv-valid-v1"
  datasetName = "cv-dataset-test"
  version = "v1"
  type = "CV"
  remark = "CV 数据集上传测试"
} | ConvertTo-Json -Compress

$cvInit = curl.exe -s -X POST "$BASE/api/dataset/upload/init" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $cvInitBody

$DATASET_UPLOAD_ID_CV = (($cvInit | ConvertFrom-Json).data.uploadId)

curl.exe -s -X POST "$BASE/api/dataset/upload/chunk" `
  -H "Authorization: Bearer $TOKEN_A" `
  -F "uploadId=$DATASET_UPLOAD_ID_CV" `
  -F "partIndex=0" `
  -F "file=@$cvFile"

$cvComplete = curl.exe -s -X POST "$BASE/api/dataset/upload/complete" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d "{`"uploadId`":`"$DATASET_UPLOAD_ID_CV`"}"

$cvComplete
$DATASET_VERSION_ID_CV_A = (($cvComplete | ConvertFrom-Json).data.id)
$DATASET_ASSET_ID_CV_A = (($cvComplete | ConvertFrom-Json).data.assetId)
```

上传 NLP 数据集 txt：

```powershell
$nlpFile = "E:\resource\TSSAIPlatform\test-files\nlp-valid.txt"
$nlpSize = (Get-Item $nlpFile).Length

$nlpInitBody = @{
  fileName = "nlp-valid.txt"
  fileSize = $nlpSize
  fileFingerprint = "nlp-valid-txt-v1"
  datasetName = "nlp-dataset-test"
  version = "v1"
  type = "NLP"
  remark = "NLP 数据集上传测试"
} | ConvertTo-Json -Compress

$nlpInit = curl.exe -s -X POST "$BASE/api/dataset/upload/init" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $nlpInitBody

$DATASET_UPLOAD_ID_NLP = (($nlpInit | ConvertFrom-Json).data.uploadId)

curl.exe -s -X POST "$BASE/api/dataset/upload/chunk" `
  -H "Authorization: Bearer $TOKEN_A" `
  -F "uploadId=$DATASET_UPLOAD_ID_NLP" `
  -F "partIndex=0" `
  -F "file=@$nlpFile"

$nlpComplete = curl.exe -s -X POST "$BASE/api/dataset/upload/complete" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d "{`"uploadId`":`"$DATASET_UPLOAD_ID_NLP`"}"

$nlpComplete
$DATASET_VERSION_ID_NLP_A = (($nlpComplete | ConvertFrom-Json).data.id)
```

上传后快速检查：

```powershell
curl.exe -s "$BASE/api/model/list" `
  -H "Authorization: Bearer $TOKEN_A"

curl.exe -s "$BASE/api/dataset/list" `
  -H "Authorization: Bearer $TOKEN_A"
```

预期：

```text
success=true
模型列表能看到 model-cv.zip 对应的版本
数据集列表能看到 cv-dataset-test 和 nlp-dataset-test
返回数据里的 ownerUserId 等于用户 A 的 id
```

## 10. 模型上传完整指令

### 10.1 计算文件大小

```powershell
$modelFile = "E:\resource\TSSAIPlatform\test-files\model-cv.zip"
$modelSize = (Get-Item $modelFile).Length
$modelSize
```

### 10.2 init

```powershell
$modelInitBody = @{
  fileName = "model-cv.zip"
  fileSize = $modelSize
  fileFingerprint = "model-cv-v1"
} | ConvertTo-Json -Compress

$modelInit = curl.exe -s -X POST "$BASE/api/model/upload/init" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $modelInitBody

$modelInit
```

记录响应里的：

```text
data.uploadId
```

设置：

```powershell
$MODEL_UPLOAD_ID = "替换为 uploadId"
```

### 10.3 chunk

```powershell
curl.exe -s -X POST "$BASE/api/model/upload/chunk" `
  -H "Authorization: Bearer $TOKEN_A" `
  -F "uploadId=$MODEL_UPLOAD_ID" `
  -F "partIndex=0" `
  -F "file=@$modelFile"
```

### 10.4 progress

```powershell
curl.exe -s "$BASE/api/model/upload/progress?uploadId=$MODEL_UPLOAD_ID" `
  -H "Authorization: Bearer $TOKEN_A"
```

预期：

```text
uploadedChunks = 1
uploadedPartIndexes = [0]
```

### 10.5 complete

```powershell
$modelCompleteBody = @{
  uploadId = $MODEL_UPLOAD_ID
  modelName = "resnet-test"
  version = "v1"
  type = "CV"
  remark = "模型上传测试"
} | ConvertTo-Json -Compress

$modelComplete = curl.exe -s -X POST "$BASE/api/model/upload/complete" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $modelCompleteBody

$modelComplete
```

记录：

```text
MODEL_VERSION_ID_A = data.id
MODEL_ASSET_ID_A = data.assetId
```

```powershell
$MODEL_VERSION_ID_A = "替换为 data.id"
$MODEL_ASSET_ID_A = "替换为 data.assetId"
```

## 11. 模型查询与预览指令

### 11.1 列表

```powershell
curl.exe -s "$BASE/api/model/list" `
  -H "Authorization: Bearer $TOKEN_A"
```

### 11.2 详情

```powershell
curl.exe -s "$BASE/api/model/detail?id=$MODEL_VERSION_ID_A" `
  -H "Authorization: Bearer $TOKEN_A"
```

### 11.3 代码文件列表

```powershell
curl.exe -s "$BASE/api/model/code-files?id=$MODEL_VERSION_ID_A" `
  -H "Authorization: Bearer $TOKEN_A"
```

如果返回里有 `train.py`：

```powershell
curl.exe -s "$BASE/api/model/previewCode?id=$MODEL_VERSION_ID_A&path=train.py" `
  -H "Authorization: Bearer $TOKEN_A"
```

## 12. 数据集上传完整指令

### 12.1 CV zip 上传

```powershell
$cvFile = "E:\resource\TSSAIPlatform\test-files\cv-valid.zip"
$cvSize = (Get-Item $cvFile).Length

$cvInitBody = @{
  fileName = "cv-valid.zip"
  fileSize = $cvSize
  fileFingerprint = "cv-valid-v1"
  datasetName = "cv-dataset-test"
  version = "v1"
  type = "CV"
  cvTaskType = "IMAGE_CLASSIFICATION"
  annotationFormat = "FOLDER_CLASSIFICATION"
  remark = "CV 数据集测试"
} | ConvertTo-Json -Compress

$cvInit = curl.exe -s -X POST "$BASE/api/dataset/upload/init" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $cvInitBody

$cvInit
```

记录：

```powershell
$DATASET_UPLOAD_ID_CV = "替换为 data.uploadId"
```

上传分片：

```powershell
curl.exe -s -X POST "$BASE/api/dataset/upload/chunk" `
  -H "Authorization: Bearer $TOKEN_A" `
  -F "uploadId=$DATASET_UPLOAD_ID_CV" `
  -F "partIndex=0" `
  -F "file=@$cvFile"
```

完成：

```powershell
$cvComplete = curl.exe -s -X POST "$BASE/api/dataset/upload/complete" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d "{`"uploadId`":`"$DATASET_UPLOAD_ID_CV`"}"

$cvComplete
```

记录：

```powershell
$DATASET_VERSION_ID_CV_A = "替换为 data.id"
$DATASET_ASSET_ID_CV_A = "替换为 data.assetId"
```

### 12.2 NLP txt 上传

```powershell
$nlpFile = "E:\resource\TSSAIPlatform\test-files\nlp-valid.txt"
$nlpSize = (Get-Item $nlpFile).Length

$nlpInitBody = @{
  fileName = "nlp-valid.txt"
  fileSize = $nlpSize
  fileFingerprint = "nlp-valid-txt-v1"
  datasetName = "nlp-dataset-test"
  version = "v1"
  type = "NLP"
  remark = "NLP txt 数据集测试"
} | ConvertTo-Json -Compress

$nlpInit = curl.exe -s -X POST "$BASE/api/dataset/upload/init" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $nlpInitBody

$nlpInit
```

记录：

```powershell
$DATASET_UPLOAD_ID_NLP = "替换为 data.uploadId"
```

上传并完成：

```powershell
curl.exe -s -X POST "$BASE/api/dataset/upload/chunk" `
  -H "Authorization: Bearer $TOKEN_A" `
  -F "uploadId=$DATASET_UPLOAD_ID_NLP" `
  -F "partIndex=0" `
  -F "file=@$nlpFile"

$nlpComplete = curl.exe -s -X POST "$BASE/api/dataset/upload/complete" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d "{`"uploadId`":`"$DATASET_UPLOAD_ID_NLP`"}"

$nlpComplete
```

记录：

```powershell
$DATASET_VERSION_ID_NLP_A = "替换为 data.id"
```

### 12.3 CV 文件夹上传

```powershell
curl.exe -s -X POST "$BASE/api/dataset/upload/folder" `
  -H "Authorization: Bearer $TOKEN_A" `
  -F "datasetName=cv-folder-test" `
  -F "version=v1" `
  -F "type=CV" `
  -F "cvTaskType=IMAGE_CLASSIFICATION" `
  -F "annotationFormat=FOLDER_CLASSIFICATION" `
  -F "remark=CV folder 测试" `
  -F "paths=cat/a.png" `
  -F "files=@E:\resource\TSSAIPlatform\test-files\images\a.png" `
  -F "paths=dog/b.png" `
  -F "files=@E:\resource\TSSAIPlatform\test-files\images\b.png"
```

预期：

```text
success=true
data.cvTaskType=IMAGE_CLASSIFICATION
data.annotationFormat=FOLDER_CLASSIFICATION
data.fileName 以 -folder.zip 结尾
```

如需验证 CV 标注文件白名单，可把 `annotationFormat` 改为 `CSV`、`YOLO`、`COCO`、`VOC` 或 `LABELME`，并在 zip 或 folder 请求里加入对应后缀的标注文件。`CSV/YOLO/COCO/VOC/LABELME` 必须至少包含一个标注文件；`NONE/FOLDER_CLASSIFICATION/MASK` 只允许图片文件。

## 13. 训练任务完整指令

前提：

```text
MODEL_VERSION_ID_A 是 CV 模型版本 ID
DATASET_VERSION_ID_CV_A 是 CV 数据集版本 ID
```

创建训练任务：

```powershell
$trainBody = @{
  name = "cv-train-test"
  modelVersionId = $MODEL_VERSION_ID_A
  datasetVersionId = $DATASET_VERSION_ID_CV_A
  codeVersionId = "code-v1"
  hyperParams = @{
    epochs = 1
    batchSize = 2
  }
  remark = "训练任务测试"
} | ConvertTo-Json -Depth 5 -Compress

$trainCreate = curl.exe -s -X POST "$BASE/api/task/create" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $trainBody

$trainCreate
```

记录：

```powershell
$EXPERIMENT_ID_A = "替换为 data.experimentId"
$TRAIN_VERSION_ID_A = "替换为 data.id"
```

查询列表：

```powershell
curl.exe -s "$BASE/api/task/list" `
  -H "Authorization: Bearer $TOKEN_A"
```

查询详情：

```powershell
curl.exe -s "$BASE/api/task/detail?id=$EXPERIMENT_ID_A" `
  -H "Authorization: Bearer $TOKEN_A"
```

停止任务：

```powershell
curl.exe -s -X POST "$BASE/api/task/stop?id=$EXPERIMENT_ID_A" `
  -H "Authorization: Bearer $TOKEN_A"
```

创建实验新版本：

```powershell
$version2Body = @{
  codeVersionId = "code-v2"
  hyperParams = @{
    epochs = 2
    batchSize = 4
  }
  remark = "第二版实验"
} | ConvertTo-Json -Depth 5 -Compress

curl.exe -s -X POST "$BASE/api/experiments/$EXPERIMENT_ID_A/versions" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $version2Body
```

查看版本历史：

```powershell
curl.exe -s "$BASE/api/experiments/$EXPERIMENT_ID_A/versions" `
  -H "Authorization: Bearer $TOKEN_A"
```

## 14. 用户隔离执行细节

### 14.1 用户 B 查询用户 A 模型

```powershell
curl.exe -s "$BASE/api/model/detail?id=$MODEL_VERSION_ID_A" `
  -H "Authorization: Bearer $TOKEN_B"
```

预期：

```text
success=false
```

### 14.2 用户 B 查询用户 A 数据集版本

```powershell
curl.exe -s "$BASE/api/dataset-versions/$DATASET_VERSION_ID_CV_A" `
  -H "Authorization: Bearer $TOKEN_B"
```

预期：

```text
success=false
```

### 14.3 用户 B 使用用户 A 资源创建训练任务

```powershell
$badTrainBody = @{
  name = "cross-owner-train"
  modelVersionId = $MODEL_VERSION_ID_A
  datasetVersionId = $DATASET_VERSION_ID_CV_A
  codeVersionId = "code-v1"
  hyperParams = @{
    epochs = 1
  }
} | ConvertTo-Json -Depth 5 -Compress

curl.exe -s -X POST "$BASE/api/task/create" `
  -H "Authorization: Bearer $TOKEN_B" `
  -H "Content-Type: application/json" `
  -d $badTrainBody
```

预期：

```text
success=false
```

### 14.4 管理员查询全部

```powershell
curl.exe -s "$BASE/api/model/list" `
  -H "Authorization: Bearer $TOKEN_ADMIN"

curl.exe -s "$BASE/api/dataset/list" `
  -H "Authorization: Bearer $TOKEN_ADMIN"

curl.exe -s "$BASE/api/task/list" `
  -H "Authorization: Bearer $TOKEN_ADMIN"
```

预期：

```text
能看到用户 A 和用户 B 的资源
```

## 15. 安全回归执行细节

### 15.1 storagePath 篡改应失败

```powershell
$hackModelVersionBody = @{
  assetId = $MODEL_ASSET_ID_A
  version = "v1"
  storagePath = "users/999/models/other.zip"
} | ConvertTo-Json -Compress

curl.exe -s -X PUT "$BASE/api/model-versions/$MODEL_VERSION_ID_A" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $hackModelVersionBody
```

预期：

```text
success=false
errorMessage=storage metadata cannot be modified
```

数据集版本同理：

```powershell
$hackDatasetVersionBody = @{
  assetId = $DATASET_ASSET_ID_CV_A
  version = "v1"
  storagePath = "users/999/datasets/other.zip"
} | ConvertTo-Json -Compress

curl.exe -s -X PUT "$BASE/api/dataset-versions/$DATASET_VERSION_ID_CV_A" `
  -H "Authorization: Bearer $TOKEN_A" `
  -H "Content-Type: application/json" `
  -d $hackDatasetVersionBody
```

### 15.2 非法 objectName 应失败

```powershell
curl.exe -s "$BASE/api/files/download?objectName=users/$USER_A_ID/../evil.txt" `
  -H "Authorization: Bearer $TOKEN_A"
```

预期：

```text
失败
```

### 15.3 token 不应完整写入日志

服务器：

```bash
grep -n "完整token内容" /opt/tss-platform/backend/backend.log
```

预期：

```text
无结果
```

## 16. 数据库检查指令

进入 PostgreSQL：

```bash
docker exec -it tss-postgres psql -U postgres -d tss
```

### 16.1 查模型版本

```sql
select id, asset_id, owner_user_id, storage_path, created_at
from model_version
order by created_at desc
limit 10;
```

检查：

```text
owner_user_id 不为空
storage_path 以 users/{owner_user_id}/models/ 开头
```

### 16.2 查数据集版本

```sql
select id, asset_id, owner_user_id, storage_path, created_at
from dataset_version
order by created_at desc
limit 10;
```

检查：

```text
owner_user_id 不为空
storage_path 以 users/{owner_user_id}/datasets/ 开头
```

### 16.3 查训练实验

```sql
select id, experiment_id, version_no, owner_user_id, status, created_at
from training_experiment_version
order by created_at desc
limit 10;
```

检查：

```text
owner_user_id 不为空
status 为 pending/running/success/failed/stopped 等约定值
```

## 17. MinIO 检查方式

### 17.1 通过 Console 看

浏览器打开：

```text
http://服务器IP:9011
```

登录后进入 bucket：

```text
models
```

检查对象是否落在：

```text
users/{userId}/models/...
users/{userId}/datasets/...
users/{userId}/files/...
```

### 17.2 通过 mc 看

如果服务器安装了 MinIO Client：

```bash
mc alias set local http://127.0.0.1:9010 admin password123
mc ls local/models/users/ --recursive
```

预期：

```text
新上传对象都在 users/ 前缀下
```

## 18. 常见问题处理

### 18.1 curl 在 PowerShell 中不好用

使用：

```powershell
curl.exe
```

不要使用：

```powershell
curl
```

### 18.2 complete 提示 zip 校验失败

检查：

```text
文件是否真的是 zip
CV zip 是否混入当前 annotationFormat 不允许的标注文件或可执行文件
NLP zip 是否混入图片/exe 等非法文件
zip 内路径是否包含 ../ 或绝对路径
```

### 18.3 用户看不到自己上传的数据

检查数据库：

```sql
select id, owner_user_id from model_version order by created_at desc limit 5;
select id, owner_user_id from dataset_version order by created_at desc limit 5;
```

确认：

```text
owner_user_id 是否等于当前登录用户 ID
前端 token 是否用错账号
```

### 18.4 管理员看不到全部

检查模块一登录响应：

```text
roleId 是否为 1 或 2
```

如果 `roleId=3`，模块二会按普通用户处理。

### 18.5 训练任务创建失败

常见原因：

```text
modelVersionId 不存在
datasetVersionId 不存在
模型和数据集 type 不一致
当前用户无权访问模型或数据集
缺少 codeVersionId
缺少 hyperParams
```

### 18.6 打包失败提示 jar 被占用

查 Java 进程：

```powershell
Get-CimInstance Win32_Process -Filter "name='java.exe'" |
  Select-Object ProcessId,CommandLine
```

终止占用进程：

```powershell
Stop-Process -Id <PID> -Force
```

重新打包：

```powershell
cd E:\resource\TSSAIPlatform\backend
.\mvnw.cmd clean package -DskipTests
```

## 19. 最小通过标准

只要下面流程全部通过，就可以认为模块二核心后端功能可进入前端联调：

1. 用户 A 登录并拿到 token。
2. 用户 A 上传一个 CV 模型 zip 成功。
3. 用户 A 上传一个 CV 数据集 zip 成功。
4. 用户 A 上传一个 NLP 数据集 txt 成功。
5. 用户 A 能查到自己的模型和数据集。
6. 用户 B 查不到用户 A 的模型和数据集。
7. 管理员能查到用户 A 的模型和数据集。
8. 用户 A 用 CV 模型 + CV 数据集创建训练任务成功。
9. 用户 A 用 CV 模型 + NLP 数据集创建训练任务失败。
10. 用户 B 不能用用户 A 的模型/数据集创建训练任务。
11. 普通用户不能修改 `storagePath`。
12. MinIO 新对象路径都在 `users/{userId}/...` 下。

## 20. 建议交付物

测试完成后建议保留：

```text
测试账号信息
测试 token 不要提交，只本地临时记录
测试文件清单
模型上传响应
数据集上传响应
训练任务创建响应
数据库截图或 SQL 查询结果
MinIO users/{userId}/ 路径截图
失败用例截图
测试结果记录表
```

这些材料可以支撑模块二联调验收。
