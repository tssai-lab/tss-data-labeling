export class WorkflowMockError extends Error {
  status: number;
  code: string;
  requestId: string;
}
export function confidenceBand(
  confidence: number | null,
  lower: number,
  upper: number,
): string;
export function majorityVote(results: unknown[]): {
  status: string;
  voteCounts: Record<string, number>;
  validVoteCount: number;
  labelId: string | null;
  voteSupport: number | null;
};
export function createWorkflowMock(options?: {
  enabled?: boolean;
  production?: boolean;
  now?: () => number;
}): {
  request(
    method: string,
    path: string,
    options?: { actor?: string; body?: unknown; key?: string },
  ): { data: unknown; requestId: string };
  reset(): unknown;
  snapshot(): unknown;
  setExportFault(
    id: string,
    options?: { failed?: boolean; expired?: boolean },
  ): void;
  finishHumanPackage(id: string): unknown;
};
