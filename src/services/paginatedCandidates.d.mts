export function collectPaginatedCandidates<T>(
  fetchPage: (
    page: number,
    pageSize: number,
  ) => Promise<{ data?: T[]; total?: number }>,
  options: {
    keyOf: (item: T) => string | undefined;
    pageSize?: number;
    maxPages?: number;
  },
): Promise<{ data: T[]; total: number }>;
