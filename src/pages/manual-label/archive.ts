import { unzip, type Unzipped } from 'fflate';

const MAX_TOTAL = 100 * 1024 * 1024;
const MAX_FILE = 20 * 1024 * 1024;

export async function readZip(bytes: Uint8Array): Promise<Unzipped> {
  if (bytes.length > MAX_TOTAL) throw new Error('ZIP 不能超过 100 MB，请使用演示子集');
  let count = 0, total = 0;
  let invalid = '';
  return new Promise((resolve, reject) => {
    let stop = (): void => {};
    const timer = setTimeout(() => { stop(); reject(new Error('ZIP 解压超时，请减少文件数量后重试')); }, 30000);
    stop = unzip(bytes, { filter: file => {
      // 先检查目录元信息再解压，限制文件数量和展开大小。
      if (!/\.(png|jpe?g|webp|csv|dat|json)$/i.test(file.name) || file.name.startsWith('__MACOSX/')) return false;
      const name = file.name.replace(/\\/g, '/');
      if (name.startsWith('/') || name.split('/').includes('..')) { invalid = '压缩包包含不安全的文件路径'; return false; }
      count++; total += file.originalSize;
      if (file.originalSize > MAX_FILE || total > MAX_TOTAL || count > 500) { invalid = 'ZIP 解压后限 500 个文件、总计 100 MB，单文件 20 MB'; return false; }
      return !invalid;
    } }, (error, files) => {
      clearTimeout(timer);
      if (invalid) reject(new Error(invalid));
      else if (error) reject(new Error('ZIP 无法解压，请确认文件未损坏且没有密码'));
      else if (!Object.keys(files).length) reject(new Error('ZIP 中没有图片、CSV、DAT 或项目 JSON；不支持嵌套压缩包'));
      else resolve(files);
    });

  });
}

export async function expandFiles(files: File[]): Promise<File[]> {
  const result: File[] = [];
  let total = 0;
  for (const file of files) {
    const entries = /\.zip$/i.test(file.name) ? await readZip(new Uint8Array(await file.arrayBuffer())) : null;
    const expanded = entries ? Object.entries(entries).map(([name, data]) => {
      const ext = name.split('.').pop()!.toLowerCase();
      const type = ({ jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp' } as Record<string, string>)[ext] || 'text/plain';
      return new File([data as Uint8Array<ArrayBuffer>], name, { type });
    }) : [file];
    for (const entry of expanded) {
      total += entry.size;
      if (total > MAX_TOTAL || result.length >= 500) throw new Error('合计最多导入 500 个文件、100 MB');
      result.push(entry);
    }
  }
  return result;
}
