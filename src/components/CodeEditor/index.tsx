import { githubLight } from '@uiw/codemirror-theme-github';
import CodeMirror from '@uiw/react-codemirror';
import React, { useMemo } from 'react';
import {
  getCodeLanguageLabel,
  getCodeMirrorExtensions,
} from '@/utils/codeLanguage';

export type CodeEditorProps = {
  value: string;
  fileName?: string;
  onChange?: (value: string) => void;
  readOnly?: boolean;
  minHeight?: string;
  maxHeight?: string;
  /** 编辑器容器 className */
  className?: string;
};

/**
 * 带语法高亮的代码编辑器（基于 CodeMirror 6）
 */
const CodeEditor: React.FC<CodeEditorProps> = ({
  value,
  fileName,
  onChange,
  readOnly = false,
  minHeight = '320px',
  maxHeight = '520px',
  className,
}) => {
  const extensions = useMemo(
    () => getCodeMirrorExtensions(fileName),
    [fileName],
  );
  const languageLabel = useMemo(
    () => getCodeLanguageLabel(fileName),
    [fileName],
  );

  return (
    <div
      className={className}
      style={{
        border: '1px solid #d9d9d9',
        borderRadius: 6,
        overflow: 'hidden',
      }}
    >
      <div
        style={{
          padding: '4px 12px',
          fontSize: 12,
          color: '#666',
          background: '#fafafa',
          borderBottom: '1px solid #f0f0f0',
        }}
      >
        {languageLabel}
        {readOnly ? ' · 只读' : ' · 可编辑'}
      </div>
      <CodeMirror
        value={value}
        height="auto"
        minHeight={minHeight}
        maxHeight={maxHeight}
        theme={githubLight}
        extensions={extensions}
        editable={!readOnly}
        readOnly={readOnly}
        onChange={(next) => onChange?.(next)}
        basicSetup={{
          lineNumbers: true,
          foldGutter: true,
          highlightActiveLine: !readOnly,
          highlightActiveLineGutter: !readOnly,
          autocompletion: false,
        }}
        style={{ fontSize: 13 }}
      />
    </div>
  );
};

export default CodeEditor;
