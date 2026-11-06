// React/browser helper. The API should be served through the frontend's same-origin proxy.
// After replacing/unmounting a preview, call URL.revokeObjectURL(returnedUrl).
export async function loadImagePreview(sample, token, signal) {
  const url = new URL(sample.fileUrl, window.location.origin);
  if (url.origin !== window.location.origin || !url.pathname.startsWith('/api/labeling/v1/projects/')) {
    throw new Error('图片地址不属于当前平台');
  }
  const response = await fetch(url, {
    headers: { Authorization: token.startsWith('Bearer ') ? token : `Bearer ${token}` }, signal
  });
  if (!response.ok) {
    const payload = await response.json();
    throw new Error(`${payload.error?.code}: ${payload.error?.message}`);
  }
  return URL.createObjectURL(await response.blob());
}
