import { Link } from '@umijs/max';
import styles from './index.less';

export default function ProjectBreadcrumbs({
  projectId,
  current = '任务发布',
}: {
  projectId?: string;
  current?: string;
}) {
  const root = projectId
    ? `/labeling/projects/${encodeURIComponent(projectId)}`
    : '/labeling';
  const target =
    current === '任务发布'
      ? `${root}?panel=publish`
      : current === '我的项目'
        ? '/labeling'
        : `${root}/workbench`;
  return (
    <nav className={styles.eyebrow} aria-label="面包屑">
      <Link to="/labeling">数据标注平台</Link>
      <span aria-hidden="true"> / </span>
      <Link to={target} aria-current="page">
        {current}
      </Link>
    </nav>
  );
}
