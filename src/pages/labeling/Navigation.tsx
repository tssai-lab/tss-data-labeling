import {
  AppstoreOutlined,
  CheckSquareOutlined,
  DownloadOutlined,
  ExportOutlined,
  FolderOutlined,
  MenuOutlined,
  SendOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { history, Link, useLocation, useModel } from '@umijs/max';
import { Button } from 'antd';
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
  project?: Pick<Project, 'projectId' | 'name'>;
  canLeave?: () => boolean;
  beforeAccountAction?: () => boolean | Promise<boolean>;
}) {
  const { pathname, search } = useLocation();
  const [mobileOpen, setMobileOpen] = useState(false);
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
    ? view === 'export'
      ? 'export'
      : 'review'
    : pathname === '/labeling/admin/users'
      ? 'users'
      : pathname.startsWith('/labeling/assignments')
        ? 'mine'
        : pathname.startsWith('/labeling/tasks')
          ? 'pool'
          : projectId
            ? 'publish'
            : 'projects';
  const links = [
    {
      key: 'projects',
      to: '/labeling',
      name: '我的项目',
      icon: <FolderOutlined />,
    },
    ...(projectId
      ? [
          {
            key: 'publish',
            to: projectId ? `${data}?panel=publish` : '/labeling',
            name: '任务发布',
            icon: <SendOutlined />,
          },
        ]
      : []),
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
            name: '结果工作台',
            icon: <CheckSquareOutlined />,
          },
          {
            key: 'export',
            to: `${workbench}?view=export`,
            name: '成果快照',
            icon: <DownloadOutlined />,
          },
        ]
      : []),
  ];
  function navigate(event: MouseEvent<HTMLAnchorElement>, to: string) {
    setMobileOpen(false);
    if (!canLeave()) {
      event.preventDefault();
      return;
    }
    if (
      !beforeAccountAction ||
      event.ctrlKey ||
      event.metaKey ||
      event.shiftKey ||
      event.altKey ||
      event.button !== 0
    )
      return;
    event.preventDefault();
    void Promise.resolve(beforeAccountAction()).then((allowed) => {
      if (allowed) history.push(to);
    });
  }
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
        <Button
          className={styles.navToggle}
          type="text"
          icon={<MenuOutlined />}
          aria-label="展开工作区导航"
          aria-expanded={mobileOpen}
          aria-controls="labeling-workspace-navigation"
          onClick={() => setMobileOpen((open) => !open)}
        />
        <PlatformBrand
          onClick={(event: MouseEvent<HTMLAnchorElement>) =>
            navigate(event, '/platforms')
          }
        />
        <Link
          to="/labeling"
          className={styles.projectSwitcher}
          onClick={(event: MouseEvent<HTMLAnchorElement>) =>
            navigate(event, '/labeling')
          }
        >
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
      <aside
        className={`${styles.sidebar} ${mobileOpen ? styles.sidebarOpen : ''}`}
        id="labeling-workspace-navigation"
      >
        <nav className={styles.navigation} aria-label="标注平台导航">
          <div className={styles.navCaption}>工作区</div>
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
                    navigate(event, item.to);
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
