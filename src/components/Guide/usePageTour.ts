import type { TourProps } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import { useGuide } from './GuideContext';
import { segmentSteps } from './tourConfig';

/**
 * 页面挂载引导段。用法：
 *   const tourProps = usePageTour(1, { ready: !loading });
 *   return <Tour {...tourProps} />;
 *
 * - 仅当当前流程落在本段且页面数据就绪（ready）后自动打开；
 * - 最后一步完成 → advance() 进入下一段路由；
 * - 关闭/跳过 → skip()（写 TOUR_SEEN）。
 */
export function usePageTour(
  segment: number,
  opts: {
    /** 页面数据是否就绪；false 时等待，避免 target 元素缺失 */
    ready?: boolean;
    /** 外部受控 current（如 S3 需与向导同步时使用） */
    current?: number;
    onClose?: () => void;
    onFinish?: () => void;
  } = {},
): TourProps {
  const { segment: activeSegment, advance, skip } = useGuide();
  const steps = useMemo(() => segmentSteps(segment), [segment]);
  const [open, setOpen] = useState(false);
  const [internalCurrent, setInternalCurrent] = useState(0);

  useEffect(() => {
    if (activeSegment !== segment) {
      setOpen(false);
      setInternalCurrent(0);
      return;
    }
    if (opts.ready === false) {
      return;
    }
    // 留出页面渲染时间，避免高亮空元素
    const timer = window.setTimeout(() => setOpen(true), 400);
    return () => window.clearTimeout(timer);
  }, [activeSegment, segment, opts.ready]);

  return {
    open,
    steps,
    current: opts.current ?? internalCurrent,
    onChange: (next) => setInternalCurrent(next),
    onClose: () => {
      setOpen(false);
      opts.onClose?.();
      skip();
    },
    onFinish: () => {
      setOpen(false);
      opts.onFinish?.();
      advance();
    },
  };
}
