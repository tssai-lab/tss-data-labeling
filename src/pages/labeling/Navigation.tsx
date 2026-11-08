import { Link, useLocation, useModel } from '@umijs/max';
import type { MouseEvent } from 'react';
import { AvatarDropdown, AvatarName } from '@/components';
import styles from './index.less';

export default function Navigation({
  canLeave = () => true,
}: {
  canLeave?: () => boolean;
}) {
  const { pathname } = useLocation();
  const { initialState } = useModel('@@initialState');
  const user = initialState?.currentUser;
  const active = pathname.startsWith('/labeling/assignments')
    ? '/labeling/assignments'
    : pathname.startsWith('/labeling/tasks')
      ? '/labeling/tasks'
      : '/labeling';

  return (
    <>
      <header className={styles.workspaceHeader}>
        <div className={styles.brand}>
          TSS <span>/</span> <small>DATA ANNOTATION</small>
        </div>
        {user && (
          <AvatarDropdown>
            <button className={styles.account} type="button" aria-label="当前登录用户">
              <AvatarName />
            </button>
          </AvatarDropdown>
        )}
      </header>
      <nav className={styles.navigation} aria-label="标注平台导航">
        <div className={styles.navTabs}>
        {[
          ['/labeling', '我的项目'],
          ['/labeling/tasks', '任务广场'],
          ['/labeling/assignments', '我的任务'],
        ].map(([to, name]) => (
          <Link
            key={to}
            to={to}
            className={`${styles.navTab} ${active === to ? styles.navTabActive : ''}`}
            onClick={(event: MouseEvent<HTMLAnchorElement>) => {
              if (!canLeave()) event.preventDefault();
            }}
          >
            {name}
          </Link>
        ))}
        </div>
      </nav>
    </>
  );
}
