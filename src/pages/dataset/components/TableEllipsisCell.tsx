import type { TooltipProps } from 'antd';
import { Tooltip } from 'antd';
import React from 'react';

const ELLIPSIS_STYLE: React.CSSProperties = {
  display: 'inline-block',
  width: '100%',
  maxWidth: '100%',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  whiteSpace: 'nowrap',
  verticalAlign: 'bottom',
};

type TableEllipsisCellProps = {
  text?: string | null;
  emptyText?: React.ReactNode;
  placement?: TooltipProps['placement'];
  /** 默认 true；文件名列等场景可关闭悬停提示 */
  showTooltip?: boolean;
};

/** 表格单元格内按列宽省略，可选悬停显示完整文本 */
const TableEllipsisCell: React.FC<TableEllipsisCellProps> = ({
  text,
  emptyText = '-',
  placement = 'topLeft',
  showTooltip = true,
}) => {
  const value = text?.trim();
  if (!value) {
    return <>{emptyText}</>;
  }
  const content = <span style={ELLIPSIS_STYLE}>{value}</span>;
  if (!showTooltip) {
    return content;
  }
  return (
    <Tooltip placement={placement} title={value}>
      {content}
    </Tooltip>
  );
};

export default TableEllipsisCell;
