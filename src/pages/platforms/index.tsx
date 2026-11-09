import {
  AppstoreOutlined,
  ArrowRightOutlined,
  DeploymentUnitOutlined,
} from '@ant-design/icons';
import { Link, useModel } from '@umijs/max';
import { useEffect } from 'react';
import { AvatarDropdown, AvatarName } from '@/components';
import { redirectToLogin } from '@/utils/loginRedirect';
import styles from './index.less';

export default function Platforms() {
  const { initialState } = useModel('@@initialState');
  const user = initialState?.currentUser;
  useEffect(() => {
    if (!user) redirectToLogin({ replace: true });
  }, [user]);
  if (!user) return null;

  return (
    <main className={styles.page}>
      <div className={styles.glow} aria-hidden="true" />
      <header className={styles.header}>
        <Link
          to="/platforms"
          className={styles.brand}
          aria-label="TSS AI 平台选择"
        >
          <strong>
            TSS<span>/</span>
          </strong>
          <small>AI PLATFORM</small>
        </Link>
        <AvatarDropdown>
          <button className={styles.account} type="button">
            <AvatarName />
          </button>
        </AvatarDropdown>
      </header>
      <section className={styles.content} aria-labelledby="platform-title">
        <div className={styles.intro}>
          <span className={styles.eyebrow}>YOUR AI WORKSPACE</span>
          <h1 id="platform-title">
            一个空间，
            <br />
            开启 AI 的更多可能。
          </h1>
          <p>
            欢迎回来，{user.name || user.username}
            。选择一个平台，开始今天的工作。
          </p>
        </div>
        <div className={styles.grid}>
          <Link to="/dashboard" className={`${styles.card} ${styles.training}`}>
            <div className={styles.cardTop}>
              <DeploymentUnitOutlined />
              <span className={styles.live}>可用</span>
            </div>
            <div className={styles.orbit} aria-hidden="true">
              <i />
              <i />
              <i />
              <b>TSS</b>
            </div>
            <div className={styles.cardBody}>
              <small>MODEL TRAINING</small>
              <h2>训练平台</h2>
              <p>管理数据与模型，开展训练和推理</p>
            </div>
            <div className={styles.cardBottom}>
              <span>进入训练平台</span>
              <ArrowRightOutlined />
            </div>
          </Link>
          <Link
            to="/labeling"
            className={`${styles.card} ${styles.pending}`}
            aria-labelledby="label-platform-title"
          >
            <div className={styles.cardTop}>
              <AppstoreOutlined />
              <span className={styles.badge}>图片单标签分类</span>
            </div>
            <div className={styles.visual} aria-hidden="true">
              <AppstoreOutlined className={styles.shield} />
            </div>
            <div className={styles.cardBody}>
              <small>DATA ANNOTATION</small>
              <h2 id="label-platform-title">数据标注平台</h2>
            </div>
            <div className={styles.cardBottom}>
              <span>进入数据标注平台</span>
              <ArrowRightOutlined />
            </div>
          </Link>
        </div>
      </section>
      <footer className={styles.footer}>
        <span>TSS AI 平台 · 数据标注与模型训练</span>
        <span>数据 · 标注 · 训练</span>
      </footer>
    </main>
  );
}
