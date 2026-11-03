type StorageSetOptions = {
  /**
   * true → localStorage（关闭浏览器仍保留）
   * false → sessionStorage（关闭标签/窗口后清除）
   * 默认 true，兼容原有调用
   */
  persist?: boolean;
};

function readRaw(store: Storage, key: string): string | null {
  try {
    return store.getItem(key);
  } catch {
    return null;
  }
}

function writeRaw(store: Storage, key: string, value: string): void {
  try {
    store.setItem(key, value);
  } catch {
    // 隐私模式等可能导致写入失败，忽略以免阻断登录
  }
}

function removeRaw(store: Storage, key: string): void {
  try {
    store.removeItem(key);
  } catch {
    // ignore
  }
}

function parseStored<T>(raw: string | null): T | null {
  if (!raw) return null;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return null;
  }
}

// 存储工具：统一处理 token 等持久化数据
export const storage = {
  set<T = any>(key: string, data: T, options?: StorageSetOptions): void {
    const persist = options?.persist !== false;
    const primary = persist ? localStorage : sessionStorage;
    const secondary = persist ? sessionStorage : localStorage;
    // 同一 key 只保留一份，避免勾选切换后两边都有旧 token
    removeRaw(secondary, key);
    writeRaw(primary, key, JSON.stringify(data));
  },

  get<T = any>(key: string): T | null {
    // 优先读当前会话（未勾选自动登录），再读持久化
    const fromSession = parseStored<T>(readRaw(sessionStorage, key));
    if (fromSession !== null) return fromSession;
    return parseStored<T>(readRaw(localStorage, key));
  },

  remove(key: string): void {
    removeRaw(localStorage, key);
    removeRaw(sessionStorage, key);
  },

  clear(): void {
    try {
      localStorage.clear();
    } catch {
      // ignore
    }
    try {
      sessionStorage.clear();
    } catch {
      // ignore
    }
  },
};

// 存储键常量：统一管理所有存储键
export const STORAGE_KEYS = {
  TOKEN: 'token',
  USER_INFO: 'USER_INFO',
  /** 训练代码管理员审核开关（与系统配置同步的前端缓存） */
  TRAINING_CODE_REVIEW_CONFIG: 'TRAINING_CODE_REVIEW_CONFIG',
  /** 教学引导是否已看过（弹一次，跳过/关闭即写入） */
  TOUR_SEEN: 'tour_seen',
};
