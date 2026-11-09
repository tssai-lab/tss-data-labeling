import { Link, useLocation, useModel } from '@umijs/max';
import { type MouseEvent, useEffect, useState } from 'react';
import { AvatarDropdown, AvatarName } from '@/components';
import { tasks } from '@/services/labelingTasks';
import styles from './index.less';

export default function Navigation({
  canLeave = () => true,
  beforeAccountAction,
}: {
  canLeave?: () => boolean;
  beforeAccountAction?: () => boolean | Promise<boolean>;
}) {
  const { pathname } = useLocation();
  const [admin, setAdmin] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    void tasks
      .me(abort.signal)
      .then((me) => {
        if (!abort.signal.aborted) setAdmin(me.labelingAdmin);
      })
      .catch(() => setAdmin(false));
    return () => abort.abort();
  }, [pathname]);
  const { initialState } = useModel('@@initialState');
  const user = initialState?.currentUser;
  const active =
    pathname === '/labeling/admin/users'
      ? pathname
      : pathname.startsWith('/labeling/assignments')
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
          <AvatarDropdown beforeAction={beforeAccountAction}>
            <button
              className={styles.account}
              type="button"
              aria-label="当前登录用户"
            >
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
            ...(admin ? [['/labeling/admin/users', '用户管理']] : []),
            ...(process.env.LABELING_GAP_MOCK === 'true' &&
            process.env.NODE_ENV !== 'production'
              ? [['/labeling/demo', '本地能力演示']]
              : []),
            ['/platforms', '切换平台'],
          ].map(([to, name]) => (
            <Link
              key={to}
              to={to}
              aria-current={active === to ? 'page' : undefined}
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
