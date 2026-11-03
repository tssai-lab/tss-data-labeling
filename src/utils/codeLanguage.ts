import { javascript } from '@codemirror/lang-javascript';
import { json } from '@codemirror/lang-json';
import { markdown } from '@codemirror/lang-markdown';
import { python } from '@codemirror/lang-python';
import { xml } from '@codemirror/lang-xml';
import { yaml } from '@codemirror/lang-yaml';
import type { Extension } from '@codemirror/state';

/** 根据文件名推断 CodeMirror 语言扩展 */
export function getCodeMirrorExtensions(fileName?: string): Extension[] {
  const ext = fileName?.split('.').pop()?.toLowerCase();
  switch (ext) {
    case 'py':
      return [python()];
    case 'json':
    case 'jsonl':
      return [json()];
    case 'yaml':
    case 'yml':
      return [yaml()];
    case 'md':
      return [markdown()];
    case 'js':
    case 'jsx':
      return [javascript({ jsx: true })];
    case 'ts':
    case 'tsx':
      return [javascript({ typescript: true, jsx: ext === 'tsx' })];
    case 'xml':
      return [xml()];
    default:
      return [];
  }
}

/** 展示用语言标签 */
export function getCodeLanguageLabel(fileName?: string): string {
  const ext = fileName?.split('.').pop()?.toLowerCase();
  const labels: Record<string, string> = {
    py: 'Python',
    json: 'JSON',
    jsonl: 'JSON Lines',
    yaml: 'YAML',
    yml: 'YAML',
    md: 'Markdown',
    txt: 'Plain Text',
    js: 'JavaScript',
    jsx: 'JSX',
    ts: 'TypeScript',
    tsx: 'TSX',
    xml: 'XML',
  };
  return labels[ext ?? ''] ?? (ext ? ext.toUpperCase() : 'Plain Text');
}
