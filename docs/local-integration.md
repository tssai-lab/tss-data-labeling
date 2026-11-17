# 本地前端连接服务器联调

## 准备

拉取 frontend 分支，进入包含 package.json 的目录。使用 Node.js 20 及以上，首次运行执行 npm ci。

联调依赖开发服务器的代理配置。Git clone 只复制源码，不复制终端环境变量和被 Git 忽略的 .env.local。没有设置目标时，登录接口默认转发到本机 8080；本机没有后端时会返回 504，并不能据此判断密码错误。

## 持久配置（Windows PowerShell）

在项目根目录创建 .env.local，保存以下已使用的联调环境地址：

```dotenv
DEV_API_TARGET=http://47.111.225.144:8001
DEV_LABELING_TARGET=http://47.111.225.144
LABEL_PROXY_TARGET=http://47.111.225.144:8001
DEV_MLFLOW_TARGET=http://47.111.225.144:8001
DEV_MLFLOW_THROUGH_GATEWAY=true
```

随后运行：

```powershell
Set-Location D:\code\GitHub\tssai
npm run dev:local
```

打开 http://127.0.0.1:8001/#/user/login。修改 .env.local 后必须停止并重新启动开发服务器，刷新浏览器不会重载代理。8001 被旧实例占用时，先停止旧实例，避免 Umi 自动改到另一端口、浏览器却仍访问旧服务。

- 登录和训练接口连接服务器 8001 环境。
- 新标注接口通过服务器 80 端口转发至标注服务。
- 旧手动标注和 MLflow 接口通过服务器 8001 网关转发。
- dev:local 仅固定监听地址、端口和开发模式，不会覆盖 DEV_API_TARGET；Umi 会读取根目录 .env.local。
- .env.local 已被 Git 忽略，不要提交密码、令牌、私钥。更换环境时修改这个本机文件。
- 需要连接本机后端时，可参考根目录 .env.local.example 中的回环地址。

## 临时配置（未使用 .env.local 时）

```powershell
$env:HOST = '127.0.0.1'
$env:PORT = '8001'
$env:DEV_API_TARGET = 'http://47.111.225.144:8001'
$env:DEV_LABELING_TARGET = 'http://47.111.225.144'
npm run start:dev
```

Linux/macOS 可继续使用：

```bash
npx cross-env HOST=127.0.0.1 PORT=8001 DEV_API_TARGET=http://47.111.225.144:8001 DEV_LABELING_TARGET=http://47.111.225.144 npm run start:dev
```

环境变量仅在当前终端有效。Umi 的 .env.local 会覆盖同名终端变量，已有该文件时应直接修改文件。以终端显示的实际端口为准；使用远程开发时，需要转发该端口。

## 验证和排错

```powershell
curl.exe -i http://127.0.0.1:8001/api/user/current-user
curl.exe -i http://127.0.0.1:8001/api/labeling/v1/health
```

未登录时 current-user 应返回后端 JSON 的 401「请先登录」，表示代理已通；health 应返回 200、status=UP。若返回 504，检查配置是否保存到正确项目根目录、服务是否已重启、目标地址是否可达。账号或密码是否有效，仍需使用已有账号实际登录验证。

服务器标注服务必须使用与前端登录相同的身份来源；当前环境为服务器本机 http://127.0.0.1:8001。

## 注意

本地页面操作会写入服务器数据，请给测试项目加上人员标识。账号权限保存在服务器数据库中，不随 Git 代码复制。开发代理不影响生产 Nginx 配置。后端尚未提供的业务接口可使用显式开启的独立演示模式；真实登录故障应修复连接配置。
