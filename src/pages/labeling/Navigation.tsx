import {
  AppstoreOutlined,
  CheckSquareOutlined,
  DownloadOutlined,
  ExportOutlined,
  SendOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { Link, useLocation, useModel } from '@umijs/max';
import { type MouseEvent, useEffect, useState } from 'react';
import { AvatarDropdown, AvatarName } from '@/components';
import type { Project } from '@/services/labeling';
import { tasks } from '@/services/labelingTasks';
import styles from './index.less';
import PlatformBrand from './PlatformBrand';

export default function Navigation({
  project,
  canLeave = () => true,
  beforeAccountAction,
}: {
  project?: Project;
  canLeave?: () => boolean;
  beforeAccountAction?: () => boolean | Promise<boolean>;
}) {
  const { pathname, search } = useLocation();
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
  const routeProject = pathname.match(/^\/labeling\/projects\/([^/]+)/)?.[1];
  const projectId = project?.projectId
    ? encodeURIComponent(project.projectId)
    : routeProject;
  const data = projectId ? `/labeling/projects/${projectId}` : '/labeling';
  const workbench = `${data}/workbench`;
  const view = new URLSearchParams(search).get('view') || 'review';
  const inWorkbench = pathname.endsWith('/workbench');
  const active = inWorkbench
    ? view
    : pathname === '/labeling/admin/users'
      ? 'users'
      : pathname.startsWith('/labeling/assignments')
        ? 'mine'
        : pathname.startsWith('/labeling/tasks')
          ? 'pool'
          : 'publish';
  const links = [
    {
      key: 'publish',
      to: projectId ? `${data}?panel=publish` : '/labeling',
      name: '任务发布',
      icon: <SendOutlined />,
    },
    {
      key: 'pool',
      to: '/labeling/tasks',
      name: '任务广场',
      icon: <AppstoreOutlined />,
    },
    ...(projectId
      ? [
          {
            key: 'review',
            to: workbench,
            name: '结果审核',
            icon: <CheckSquareOutlined />,
          },
          {
            key: 'export',
            to: `${workbench}?view=export`,
            name: '成果导出',
            icon: <DownloadOutlined />,
          },
        ]
      : []),
  ];
  const secondary = [
    {
      key: 'mine',
      to: '/labeling/assignments',
      name: '我的任务',
      icon: <UserOutlined />,
    },
    ...(admin
      ? [
          {
            key: 'users',
            to: '/labeling/admin/users',
            name: '用户管理',
            icon: <TeamOutlined />,
          },
        ]
      : []),
    {
      key: 'platforms',
      to: '/platforms',
      name: '切换平台',
      icon: <ExportOutlined />,
    },
  ];
  return (
    <>
      <header className={styles.workspaceHeader}>
        <PlatformBrand />
        <Link to="/labeling" className={styles.projectSwitcher}>
          项目：{project?.name || '选择项目'} <span aria-hidden="true">⌄</span>
        </Link>
        <div className={styles.headerAccount}>
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
        </div>
      </header>
      <aside className={styles.sidebar}>
        <nav className={styles.navigation} aria-label="标注平台导航">
          {[links, secondary].map((group, index) => (
            <div
              className={styles.navGroup}
              key={index === 0 ? 'primary' : 'secondary'}
            >
              {group.map((item) => (
                <Link
                  key={item.key}
                  to={item.to}
                  aria-current={active === item.key ? 'page' : undefined}
                  className={`${styles.navTab} ${active === item.key ? styles.navTabActive : ''}`}
                  onClick={(event: MouseEvent<HTMLAnchorElement>) => {
                    if (!canLeave()) event.preventDefault();
                  }}
                >
                  <span aria-hidden="true">{item.icon}</span>
                  <span>{item.name}</span>
                </Link>
              ))}
            </div>
          ))}
        </nav>
      </aside>
    </>
  );
}
