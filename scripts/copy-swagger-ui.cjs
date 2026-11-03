/**
 * 将 swagger-ui-dist 的静态资源复制到 public/ 目录，
 * 供生产环境本地加载，无需依赖外网 CDN。
 */
const fs = require('node:fs');
const path = require('node:path');

const srcDir = path.join(__dirname, '..', 'node_modules', 'swagger-ui-dist');
const destDir = path.join(__dirname, '..', 'public', 'swagger-ui');

const files = ['swagger-ui.css', 'swagger-ui-bundle.js'];

if (!fs.existsSync(destDir)) {
  fs.mkdirSync(destDir, { recursive: true });
}

for (const file of files) {
  const src = path.join(srcDir, file);
  const dest = path.join(destDir, file);
  if (fs.existsSync(src)) {
    fs.copyFileSync(src, dest);
    const stat = fs.statSync(dest);
    console.log(`✓ copied ${file} (${(stat.size / 1024).toFixed(1)} KB)`);
  } else {
    console.error(`✗ source not found: ${src}`);
    process.exitCode = 1;
  }
}

console.log('Swagger UI assets ready.');
