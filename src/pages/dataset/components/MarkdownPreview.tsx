import React from 'react';
import type { Components } from 'react-markdown';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import './markdown-preview.css';

type Props = {
  content: string;
  maxHeight: string | number;
};

/** 必须模块级稳定：react-markdown 若每次拿到新数组，会在 effect 里反复 setState */
const REMARK_PLUGINS = [remarkGfm];

type MdHostProps = React.HTMLAttributes<HTMLElement> & {
  node?: unknown;
  children?: React.ReactNode;
};

/** 去掉 hast node（含循环引用），避免塞进 DOM 触发 React #185 死循环 */
function host(tag: keyof React.JSX.IntrinsicElements) {
  const MarkdownHost = ({ node: _node, children, ...props }: MdHostProps) =>
    React.createElement(tag, props, children);
  MarkdownHost.displayName = `Markdown(${tag})`;
  return MarkdownHost;
}

const markdownComponents: Components = {
  a: ({ node: _node, children, ...props }) => (
    <a {...props} target="_blank" rel="noreferrer noopener">
      {children}
    </a>
  ),
  p: host('p'),
  h1: host('h1'),
  h2: host('h2'),
  h3: host('h3'),
  h4: host('h4'),
  h5: host('h5'),
  h6: host('h6'),
  ul: host('ul'),
  ol: host('ol'),
  li: host('li'),
  pre: host('pre'),
  code: host('code'),
  em: host('em'),
  strong: host('strong'),
  blockquote: host('blockquote'),
  hr: host('hr'),
  br: host('br'),
  img: host('img'),
  table: host('table'),
  thead: host('thead'),
  tbody: host('tbody'),
  tr: host('tr'),
  th: host('th'),
  td: host('td'),
  del: host('del'),
  section: host('section'),
  div: host('div'),
  span: host('span'),
  input: host('input'),
};

const MarkdownPreview: React.FC<Props> = ({ content, maxHeight }) => (
  <div className="dataset-markdown-preview" style={{ maxHeight }}>
    <ReactMarkdown
      remarkPlugins={REMARK_PLUGINS}
      components={markdownComponents}
    >
      {content}
    </ReactMarkdown>
  </div>
);

export default MarkdownPreview;
