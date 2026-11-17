/** Platform help is served by the owning application on the same origin. */
export function getDocsBaseUrl(): string {
  return `${window.location.origin}${window.location.pathname}#/labeling/help`;
}

export function getDocsUrl(pathOSS: string, _pathEnterprise?: string): string {
  return `${getDocsBaseUrl()}?topic=${encodeURIComponent(pathOSS)}`;
}
