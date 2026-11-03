export type TextRowInputPreview = {
  kind: 'text';
  name: string;
  summary: string;
  text: string;
  path: string;
  truncated: boolean;
  contentTruncated: boolean;
};

export type RowInputPreview =
  | { kind: 'image'; path: string; name: string }
  | { kind: 'file'; path: string; name: string }
  | TextRowInputPreview;

export const INLINE_TEXT_LIMIT: number;
export function safeRelativePreviewPath(value: unknown): string;
export function resolveRowInputPreview(row: unknown): RowInputPreview | null;
