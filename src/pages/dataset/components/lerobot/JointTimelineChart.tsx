import React, { useEffect, useRef } from 'react';

type Props = {
  name: string;
  state: number[];
  action: number[];
  progress: number;
  stateValue: number;
  actionValue: number;
};

const JointTimelineChart: React.FC<Props> = ({
  name,
  state,
  action,
  progress,
  stateValue,
  actionValue,
}) => {
  const canvasRef = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const draw = () => {
      const rect = canvas.getBoundingClientRect();
      const dpr = window.devicePixelRatio || 1;
      canvas.width = Math.max(1, Math.round(rect.width * dpr));
      canvas.height = Math.max(1, Math.round(rect.height * dpr));
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.scale(dpr, dpr);
      const width = rect.width;
      const height = rect.height;
      const pad = { left: 48, right: 16, top: 8, bottom: 8 };
      const values = [...state, ...action].filter(Number.isFinite);
      let min = values.length ? Math.min(...values) : 0;
      let max = values.length ? Math.max(...values) : 1;
      const gap = Math.max((max - min) * 0.1, 0.01);
      min -= gap;
      max += gap;
      const x = (index: number) =>
        pad.left +
        (index / Math.max(state.length - 1, 1)) *
          (width - pad.left - pad.right);
      const y = (value: number) =>
        pad.top +
        ((max - value) / Math.max(max - min, 0.001)) *
          (height - pad.top - pad.bottom);
      ctx.font = '11px Segoe UI';
      ctx.textAlign = 'right';
      ctx.textBaseline = 'middle';
      for (let index = 0; index <= 2; index += 1) {
        const value = max - (index / 2) * (max - min);
        const py = y(value);
        ctx.strokeStyle = '#343a40';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(pad.left, py);
        ctx.lineTo(width - pad.right, py);
        ctx.stroke();
        ctx.fillStyle = '#8c959f';
        ctx.fillText(value.toFixed(max - min < 10 ? 1 : 0), pad.left - 8, py);
      }
      const line = (series: number[], color: string, dash: number[]) => {
        ctx.strokeStyle = color;
        ctx.lineWidth = dash.length ? 1.25 : 1.75;
        ctx.setLineDash(dash);
        ctx.beginPath();
        series.forEach((value, index) => {
          if (index === 0) ctx.moveTo(x(index), y(value));
          else ctx.lineTo(x(index), y(value));
        });
        ctx.stroke();
      };
      line(state, '#4fd19a', []);
      line(action, '#f2bd45', [5, 4]);
      ctx.setLineDash([]);
    };
    draw();
    const observer = new ResizeObserver(draw);
    observer.observe(canvas);
    return () => observer.disconnect();
  }, [state, action]);

  const delta = actionValue - stateValue;
  return (
    <article className="lerobot-joint-plot">
      <div className="lerobot-joint-header">
        <strong>{name.replace(/^main_/, '')}</strong>
        <div className="lerobot-joint-values">
          <span className="is-state">
            观测 <b>{stateValue.toFixed(3)}</b>
          </span>
          <span className="is-action">
            动作 <b>{actionValue.toFixed(3)}</b>
          </span>
          <span>
            差值{' '}
            <b
              className={
                delta > 0 ? 'is-positive' : delta < 0 ? 'is-negative' : ''
              }
            >
              {delta >= 0 ? '+' : ''}
              {delta.toFixed(3)}
            </b>
          </span>
        </div>
      </div>
      <div className="lerobot-chart-wrap">
        <canvas ref={canvasRef} />
        <div
          className="lerobot-chart-cursor"
          style={{
            left: `calc(48px + ${Math.max(0, Math.min(progress, 1))} * (100% - 64px))`,
          }}
        />
      </div>
    </article>
  );
};

export default JointTimelineChart;
