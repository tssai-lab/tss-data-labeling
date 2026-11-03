import React, { useEffect, useRef } from 'react';

export type ResizableTitleProps =
  React.ThHTMLAttributes<HTMLTableCellElement> & {
    width?: number;
    onResize?: (width: number) => void;
    minWidth?: number;
  };

/** 表头拖拽手柄，配合 Ant Design Table components.header.cell 使用 */
const ResizableTitle: React.FC<ResizableTitleProps> = (props) => {
  const {
    width,
    onResize,
    minWidth = 48,
    children,
    style,
    title: _title,
    ...rest
  } = props;
  const draggingRef = useRef(false);
  const startRef = useRef({ x: 0, width: 0 });

  useEffect(() => {
    const onMouseMove = (event: MouseEvent) => {
      if (!draggingRef.current || !onResize) return;
      const nextWidth = Math.max(
        minWidth,
        startRef.current.width + event.clientX - startRef.current.x,
      );
      onResize(nextWidth);
    };
    const onMouseUp = () => {
      draggingRef.current = false;
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
    };

    document.addEventListener('mousemove', onMouseMove);
    document.addEventListener('mouseup', onMouseUp);
    return () => {
      document.removeEventListener('mousemove', onMouseMove);
      document.removeEventListener('mouseup', onMouseUp);
    };
  }, [minWidth, onResize]);

  if (width == null || !onResize) {
    return (
      <th {...rest} title={undefined} style={style}>
        {children}
      </th>
    );
  }

  return (
    <th
      {...rest}
      title={undefined}
      style={{
        ...style,
        position: 'relative',
        width,
        maxWidth: width,
      }}
    >
      {children}
      <span
        aria-hidden="true"
        onMouseDown={(event) => {
          event.preventDefault();
          event.stopPropagation();
          draggingRef.current = true;
          startRef.current = { x: event.clientX, width };
          document.body.style.cursor = 'col-resize';
          document.body.style.userSelect = 'none';
        }}
        onClick={(event) => event.stopPropagation()}
        style={{
          position: 'absolute',
          right: -4,
          top: 0,
          bottom: 0,
          width: 8,
          cursor: 'col-resize',
          zIndex: 1,
        }}
      />
    </th>
  );
};

export default ResizableTitle;
