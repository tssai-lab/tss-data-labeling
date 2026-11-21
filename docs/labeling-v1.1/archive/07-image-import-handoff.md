# 图片导入交付与分支协作

本轮负责人：汤笑诚。后端目录：`labeling-service/`，训练后端仍在 `backend/`，两者不要混淆。

## 团队分支

`backend`、`frontend` 是同一个 GitHub 仓库下的分支。在仓库页面文件列表左上方的分支选择器切换，或者打开 Branches 页面查看。它们不是 GitHub 仓库名称或两个独立 GitHub 网址。

2026-11-05 拉取核对：`backend` 提交 `963a28e` 已合入 `labeling-backend`，当时两分支文件一致。该结论只是此次检查时的状态；以后开发前仍须更新。

## 首次本地开发

```powershell
git clone --branch backend https://github.com/tssai-lab/tss-data-labeling.git
cd tss-data-labeling
git fetch origin
git switch -c feat/tangxiaocheng-image-import origin/backend
```

本次助手已在独立的本地克隆建立同名功能分支，无须再对这份克隆重复创建。

## 每次继续开发前

先用 `git status` 查看本地改动；有改动时先自行整理为本地提交，再执行下面的更新，避免把尚未保存的工作混进合并。

```powershell
git switch feat/tangxiaocheng-image-import
git fetch origin backend
git merge origin/backend
```

发生冲突时处理冲突并重新验证。不要通过覆盖整个目录、丢弃他人提交或强制推送解决。

## 测试和上传

在 `labeling-service/` 中，使用 Java 17 和 Maven：

```powershell
mvn test
mvn package
```

测试使用临时的真实 PostgreSQL，不连接生产库；首次会下载相关二进制。Linux 以普通用户运行测试，PostgreSQL initdb 不支持 root 用户。

检查差异后，只提交本次文件，在功能分支推送：

```powershell
git diff --check
git status
# 检查后用 git add <本次修改的文件> 暂存
git commit -m "feat: add image archive imports and authorized sample files"
git push -u origin feat/tangxiaocheng-image-import
```

在 GitHub 创建 Pull Request：base=`backend`，compare=`feat/tangxiaocheng-image-import`。让负责人检查后再合并；不要直接推送到 `backend`，也不要把后端功能分支合并到 `frontend`。

## 联调验收范围

真实数据集尚未确定，本地先使用自动生成的 JPEG/PNG、中文路径、损坏文件及恶意 ZIP 验证。生成图片的测试通过不能替代真实数据集验收。

前端交付需包含：真实服务地址、现有登录 Token 取得方式、项目 ID、批次 ID、样本 ID、上传/轮询/分页/图片预览示例，以及逐文件错误响应。Token 不写进文档、代码或提交。

本轮不实现领取、分类保存、Label Studio、提交或导出。图片导入成功只表示数据可用；宋雷鸣和汤立娜仍需接各自负责的保存与同步链路。
