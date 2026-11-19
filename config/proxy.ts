/**
 * 仅供 Umi 开发服务器使用；生产静态站点的反向代理由部署网关配置。
 * 开发默认连接团队共享服务器；个人环境可通过环境变量覆盖目标地址。
 * 该代理仅由本地 Umi 开发服务器使用，不影响生产静态站点。
 */
const apiTarget =
  process.env.DEV_API_TARGET || 'http://47.111.225.144:8001';
const mlflowTarget = process.env.DEV_MLFLOW_TARGET || 'http://127.0.0.1:5000';
const proxy = {
  '/label-api/': {
    target: process.env.LABEL_PROXY_TARGET || 'http://127.0.0.1:8013',
    changeOrigin: true,
  },
  '/api/labeling/': {
    target:
      process.env.DEV_LABELING_TARGET || 'http://47.111.225.144:8001',
    changeOrigin: true,
    // Preserve editor-ui / editor-assets paths and rewrite upstream cookies for same-origin launch.
    cookieDomainRewrite: '',
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
