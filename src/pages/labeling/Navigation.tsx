import {
  AppstoreOutlined,
  ExportOutlined,
  FolderOutlined,
  MenuOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { history, Link, useLocation, useModel } from '@umijs/max';
import { Button } from 'antd';
import { type MouseEvent, useEffect, useState } from 'react';
import { AvatarDropdown, AvatarName } from '@/components';
import { tasks } from '@/services/labelingTasks';
import styles from './index.less';
import PlatformBrand from './PlatformBrand';

export default function Navigation({
  canLeave = () => true,
  beforeAccountAction,
}: {
  canLeave?: () => boolean;
  beforeAccountAction?: () => boolean | Promise<boolean>;
}) {
  const { pathname } = useLocation();
  const [mobileOpen, setMobileOpen] = useState(false);
  const [admin, setAdmin] = useState(false);
  useEffect(() => {
    const abort = new AbortController();
    setAdmin(false);
    void tasks
      .me(abort.signal)
      .then((me) => {
        if (!abort.signal.aborted) {
          setAdmin(me.labelingAdmin);
        }
      })
      .catch(() => {
        if (!abort.signal.aborted) setAdmin(false);
      });
    return () => abort.abort();
  }, []);
  const { initialState } = useModel('@@initialState');
  const user = initialState?.currentUser;
  const active =
    pathname === '/labeling/admin/users'
      ? 'users'
      : pathname.startsWith('/labeling/assignments')
        ? 'mine'
        : pathname.startsWith('/labeling/tasks')
          ? 'pool'
          : 'projects';
  const links = [
    {
      key: 'projects',
      to: '/labeling',
      name: '我的项目',
      icon: <FolderOutlined />,
    },
    {
      key: 'pool',
      to: '/labeling/tasks',
      name: '任务广场',
      icon: <AppstoreOutlined />,
    },
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
        <PlatformBrand interactive={false} />
      </header>
      <aside
        className={`${styles.sidebar} ${mobileOpen ? styles.sidebarOpen : ''}`}
        id="labeling-workspace-navigation"
      >
        <nav className={styles.navigation} aria-label="标注平台导航">
          <div className={styles.sidebarBrand}>
            <PlatformBrand interactive={false} />
          </div>
          {[links, secondary].map((group, index) => (
            <div
              className={`${styles.navGroup} ${index === 1 ? styles.navGroupSecondary : ''}`}
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
          <div className={styles.navFooter}>
            {user && (
              <AvatarDropdown
                beforeAction={beforeAccountAction}
                trigger={['click']}
                placement="topRight"
              >
                <button
                  className={styles.footerAccount}
                  type="button"
                  aria-label="当前登录用户"
                >
                  <span className={styles.footerAvatar} aria-hidden="true">
                    <UserOutlined />
                  </span>
                  <AvatarName />
                </button>
              </AvatarDropdown>
            )}
            <Link
              to="/platforms"
              className={styles.navTab}
              onClick={(event: MouseEvent<HTMLAnchorElement>) =>
                navigate(event, '/platforms')
              }
            >
              <span aria-hidden="true">
                <ExportOutlined />
              </span>
              <span>切换平台</span>
            </Link>
          </div>
        </nav>
      </aside>
    </>
  );
}
