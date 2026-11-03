export type ProTableRequestResult<T> = {
  data: T[];
  success: boolean;
  total: number;
};

export function withIndex<T extends Record<string, any>>(
  list: T[],
  current: number,
  pageSize: number,
): (T & { _index: number })[] {
  const currentNum = typeof current === 'number' && current > 0 ? current : 1;
  const pageSizeNum =
    typeof pageSize === 'number' && pageSize > 0 ? pageSize : 10;
  return list.map((item, index) => ({
    ...item,
    _index: (currentNum - 1) * pageSizeNum + index + 1,
  }));
}

export function toProTableSuccess<T>(
  list: T[],
  total: number,
): ProTableRequestResult<T> {
  return { data: list, success: true, total };
}

export function toProTableFail<T>(): ProTableRequestResult<T> {
  return { data: [], success: false, total: 0 };
}
