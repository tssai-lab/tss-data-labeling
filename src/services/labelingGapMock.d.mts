export class GapMockError extends Error {
  status: number;
  code: string;
  requestId: string;
}
export function createGapMock(options?: {
  enabled?: boolean;
  production?: boolean;
  now?: () => number;
}): {
  reset(): any;
  snapshot(): any;
  request(
    method: string,
    path: string,
    options?: { actor?: string; body?: any; key?: string },
  ): { data: any; requestId: string };
  setExportFault(
    id: string,
    options?: { failed?: boolean; expired?: boolean },
  ): void;
  assertWorkAccess(actor: string, assignmentId: string): boolean;
};
