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
          colorPrimary: '#3765d9',
          colorInfo: '#3765d9',
          colorText: '#172b45',
          colorTextSecondary: '#718096',
          colorBgLayout: '#f3f6fb',
          colorBorder: '#dce3ec',
          colorBorderSecondary: '#e8edf4',
          borderRadius: 10,
          fontSize: 14,
          controlHeight: 38,
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
