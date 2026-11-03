/**
 * 请求超时等跨模块公共常量。
 * 全局 request 默认超时较短（见 app.tsx），大文件下载 / 预览拉流必须单独覆盖。
 */

/**
 * 制品 / 对象下载与预览拉流超时。
 * axios / umi：`0` 表示不限制，避免大文件被前端超时掐断。
 */
export const FILE_DOWNLOAD_REQUEST_TIMEOUT = 0;
