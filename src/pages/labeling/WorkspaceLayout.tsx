import { ConfigProvider } from 'antd';
import type { PropsWithChildren } from 'react';
import type { Project } from '@/services/labeling';
import styles from './index.less';
import Navigation from './Navigation';
import { workspaceTheme, workspaceVariables } from './workspaceTheme';

export default function WorkspaceLayout({
  children,
  beforeAccountAction,
  project,
  workbench = false,
  focusMode = false,
}: PropsWithChildren<{
  beforeAccountAction?: () => boolean | Promise<boolean>;
  project?: Pick<Project, 'projectId' | 'name'>;
  workbench?: boolean;
  focusMode?: boolean;
}>) {
  return (
    <ConfigProvider theme={workspaceTheme}>
      <main
        style={workspaceVariables}
        className={`${styles.page} ${styles.workspacePage} ${focusMode ? styles.focusPage : ''}`}
      >
        <a className={styles.skipLink} href="#labeling-main-content">
          跳转到主要内容
        </a>
        <Navigation
          project={project}
          beforeAccountAction={beforeAccountAction}
        />
        <section
          id="labeling-main-content"
          tabIndex={-1}
          className={`${styles.content} ${workbench ? styles.workbenchContent : ''}`}
        >
          {children}
        </section>
      </main>
    </ConfigProvider>
  );
}
