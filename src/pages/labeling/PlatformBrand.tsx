import { Link } from '@umijs/max';
import type { MouseEventHandler } from 'react';
import styles from './index.less';

export default function PlatformBrand({
  onClick,
}: {
  onClick?: MouseEventHandler<HTMLAnchorElement>;
} = {}) {
  return (
    <Link
      to="/platforms"
      className={styles.workspaceBrand}
      aria-label="TSS AI PLATFORM · 返回平台选择"
      onClick={onClick}
    >
      <span className="tss-brand-mark" aria-hidden="true">
        T
      </span>
      <span className="tss-brand-wordmark">
        <strong>
          TSS <em>/</em>
        </strong>
        <small>AI PLATFORM</small>
      </span>
      <span className={styles.platformTitle}>数据标注平台</span>
    </Link>
  );
}
