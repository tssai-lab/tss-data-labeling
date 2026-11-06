/**
 * 仅供 Umi 开发服务器使用；生产静态站点的反向代理由部署网关配置。
 * 默认只连接本机，避免甲方调试代码时误写实验室/外网环境。
 * 远端联调必须显式设置目标地址；不按 main、second 等机器名称推断。
 */
const apiTarget = process.env.DEV_API_TARGET || 'http://127.0.0.1:8080';
const mlflowTarget = process.env.DEV_MLFLOW_TARGET || 'http://127.0.0.1:5000';
const proxy = {
  '/label-api/': { target: process.env.LABEL_PROXY_TARGET || 'http://127.0.0.1:8013', changeOrigin: true },
  '/api/labeling/': {
    target: process.env.DEV_LABELING_TARGET || apiTarget,
    changeOrigin: true,
  },
  '/api/': { target: apiTarget, changeOrigin: true },
  '/v3/api-docs': { target: apiTarget, changeOrigin: true },
  '/mlflow-api/': {
    target: mlflowTarget,
    changeOrigin: true,
    // 直连 MLflow 需要改写路径；经过平台网关时由网关负责改写。
    ...(process.env.DEV_MLFLOW_THROUGH_GATEWAY === 'true'
      ? {}
      : { pathRewrite: { '^/mlflow-api': '/ajax-api' } }),
  },
};

// 环境名只区分构建配置，不能隐式选择有写入权限的真实服务器。
export default { dev: proxy, test: proxy, pre: proxy, prod: proxy };
