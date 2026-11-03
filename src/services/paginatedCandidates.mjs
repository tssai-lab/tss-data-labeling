export async function collectPaginatedCandidates(
  fetchPage,
  { keyOf, pageSize = 200, maxPages = 1000 },
) {
  const unique = new Map();
  let page = 1;
  let total = 0;

  while (page <= maxPages) {
    const response = await fetchPage(page, pageSize);
    const data = Array.isArray(response?.data) ? response.data : [];
    total = Number.isFinite(Number(response?.total))
      ? Math.max(0, Number(response.total))
      : data.length;
    for (const item of data) {
      const key = keyOf(item);
      if (key) unique.set(key, item);
    }
    if (!data.length || page >= Math.ceil(total / pageSize)) break;
    page += 1;
  }

  return { data: [...unique.values()], total };
}
