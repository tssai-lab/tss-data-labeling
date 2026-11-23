export type ParsedLabelConfig = Record<
  string,
  { labels_attrs?: Record<string, { value?: string }> }
>;

export function formatAiCandidateLabel(
  regions?: { from_name?: string; value?: Record<string, unknown> }[],
  parsedConfig?: ParsedLabelConfig,
): string;
