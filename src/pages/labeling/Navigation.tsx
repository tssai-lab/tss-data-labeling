import { Link } from '@umijs/max';
import { Space } from 'antd';
import type { MouseEvent } from 'react';
export default function Navigation({
  canLeave = () => true,
}: {
  canLeave?: () => boolean;
}) {
  return (
    <nav aria-label="标注平台导航" style={{ margin: '20px 0' }}>
      <Space wrap size="large">
        {[
          ['/labeling', '我的项目'],
          ['/labeling/tasks', '任务广场'],
          ['/labeling/assignments', '我的任务'],
          ['/platforms', '平台选择'],
        ].map(([to, name]) => (
          <Link
            key={to}
            to={to}
            onClick={(event: MouseEvent<HTMLAnchorElement>) => {
              if (!canLeave()) event.preventDefault();
            }}
          >
            {name}
          </Link>
        ))}
      </Space>
    </nav>
  );
}
