import { createSHA256, sha256 } from 'hash-wasm';

export async function hashFile(file, signal, onProgress = () => {}) {
  const hash = await createSHA256();
  hash.init();
  const block = 4 * 1024 * 1024;
  for (let offset = 0; offset < file.size; offset += block) {
    signal.throwIfAborted();
    hash.update(
      new Uint8Array(await file.slice(offset, offset + block).arrayBuffer()),
    );
    onProgress(Math.min(file.size, offset + block) / file.size);
    // Let the browser paint progress and handle cancellation between blocks.
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  signal.throwIfAborted();
  return hash.digest('hex');
}

export async function uploadChunks(
  file,
  batch,
  request,
  signal,
  onProgress = () => {},
) {
  let offset = batch.receivedBytes;
  const chunkSize = batch.chunkSizeBytes;
  if (
    !Number.isSafeInteger(chunkSize) ||
    chunkSize <= 0 ||
    !Number.isSafeInteger(offset) ||
    offset < 0 ||
    offset > file.size ||
    batch.sizeBytes !== file.size ||
    (offset < file.size && offset % chunkSize !== 0)
  ) {
    throw new Error('服务器返回的上传进度或分片尺寸无效');
  }
  onProgress(offset / file.size);
  while (offset < file.size) {
    signal.throwIfAborted();
    const end = Math.min(file.size, offset + chunkSize);
    const bytes = new Uint8Array(await file.slice(offset, end).arrayBuffer());
    const checksum = await sha256(bytes);
    const result = await request(
      `/imports/${encodeURIComponent(batch.batchId)}/chunks?offset=${offset}`,
      {
        method: 'PUT',
        headers: {
          'Content-Type': 'application/octet-stream',
          'X-Chunk-SHA256': checksum,
        },
        body: bytes,
        signal,
      },
    );
    if (
      !Number.isSafeInteger(result.receivedBytes) ||
      result.receivedBytes < end ||
      result.receivedBytes > file.size
    ) {
      throw new Error('服务器返回的上传进度无效，请重新查询批次');
    }
    offset = result.receivedBytes;
    if (offset < file.size && offset % chunkSize !== 0)
      throw new Error('上传进度未对齐分片');
    onProgress(offset / file.size);
  }
}
