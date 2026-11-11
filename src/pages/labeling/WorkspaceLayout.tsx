import { ConfigProvider } from 'antd';
import type { PropsWithChildren } from 'react';
import type { Project } from '@/services/labeling';
import styles from './index.less';
import Navigation from './Navigation';

export default function WorkspaceLayout({
  children,
  beforeAccountAction,
  project,
  workbench = false,
}: PropsWithChildren<{
  beforeAccountAction?: () => boolean | Promise<boolean>;
  project?: Project;
  workbench?: boolean;
}>) {
  return (
    <ConfigProvider
      theme={{
        token: {
          colorPrimary: '#5145f5',
          colorBorder: '#dadcea',
          borderRadius: 8,
          fontSize: 14,
          controlHeight: 32,
        },
      }}
    >
      <main className={`${styles.page} ${styles.workspacePage}`}>
        <Navigation
          project={project}
          beforeAccountAction={beforeAccountAction}
        />
        <section
          className={`${styles.content} ${workbench ? styles.workbenchContent : ''}`}
        >
          {children}
        </section>
      </main>
    </ConfigProvider>
  );
}
