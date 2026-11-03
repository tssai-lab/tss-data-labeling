/** 一次表单提交共用办理编号；网络重试不另建任务。 */
export function createTrainingSubmission() {
  let busy = false;
  let previous = { signature: '', key: '' };
  return {
    begin() {
      if (busy) return false;
      busy = true;
      return true;
    },
    finish() { busy = false; },
    keyFor(scope: string, payload: object) {
      const signature = JSON.stringify([scope, payload], (_name, value: unknown) =>
        value && typeof value === 'object' && !Array.isArray(value)
          ? Object.fromEntries(Object.entries(value).sort(([left], [right]) => left.localeCompare(right)))
          : value,
      );
      if (signature !== previous.signature) {
        // 内网 HTTP 页面也可使用 getRandomValues，不依赖仅 HTTPS 提供的 randomUUID。
        const bytes = crypto.getRandomValues(new Uint8Array(16));
        previous = { signature, key: Array.from(bytes, byte => byte.toString(16).padStart(2, '0')).join('') };
      }
      return previous.key;
    },
  };
}
