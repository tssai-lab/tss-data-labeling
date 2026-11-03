import { history } from '@umijs/max';
import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { STORAGE_KEYS, storage } from '@/utils/storage';
import { buildSegments } from './tourConfig';

/** 引导流程的会话状态（sessionStorage 持久化，跨路由续跑） */
interface GuideState {
  /** 当前段索引：0 首页 / 1 数据集上传 / 2 模型上传 / 3 发起训练 / 4 推理 / 5 完成 */
  segment: number;
}

interface GuideContextValue {
  segment: number | null;
  /** 教学跳过/关闭后不再自动弹 */
  tourSeen: boolean;
  start: () => Promise<void>;
  advance: () => void;
  skip: () => void;
}

const GUIDE_STATE_KEY = 'guide_session_state';

function readState(): GuideState | null {
  try {
    const raw = sessionStorage.getItem(GUIDE_STATE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as GuideState;
    if (parsed && typeof parsed.segment === 'number') {
      return parsed;
    }
    return null;
  } catch {
    return null;
  }
}

function writeState(next: GuideState | null) {
  if (next) {
    sessionStorage.setItem(GUIDE_STATE_KEY, JSON.stringify(next));
  } else {
    sessionStorage.removeItem(GUIDE_STATE_KEY);
  }
}

const GuideContext = createContext<GuideContextValue>({
  segment: null,
  tourSeen: false,
  start: async () => {},
  advance: () => {},
  skip: () => {},
});

export function GuideProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<GuideState | null>(() => readState());
  const [tourSeen, setTourSeen] = useState(() =>
    Boolean(storage.get<boolean>(STORAGE_KEYS.TOUR_SEEN)),
  );

  const persist = useCallback((next: GuideState | null) => {
    writeState(next);
    setState(next);
  }, []);

  /** 从首页 S0 开始引导 */
  const start = useCallback(async () => {
    const route = buildSegments()[0].route;
    persist({ segment: 0 });
    if (history.location.pathname !== route) {
      history.push(route);
    }
  }, [persist]);

  const advance = useCallback(() => {
    if (!state) return;
    const segments = buildSegments();
    const nextSegment = state.segment + 1;
    if (nextSegment >= segments.length) {
      persist(null);
      return;
    }
    persist({ segment: nextSegment });
    const route = segments[nextSegment].route;
    if (history.location.pathname !== route) {
      history.push(route);
    }
  }, [state, persist]);

  const skip = useCallback(() => {
    storage.set(STORAGE_KEYS.TOUR_SEEN, true);
    setTourSeen(true);
    persist(null);
  }, [persist]);

  useEffect(() => {
    // 同步其它标签页写入的 tour_seen
    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEYS.TOUR_SEEN) {
        setTourSeen(Boolean(storage.get<boolean>(STORAGE_KEYS.TOUR_SEEN)));
      }
    };
    window.addEventListener('storage', onStorage);
    return () => window.removeEventListener('storage', onStorage);
  }, []);

  const value = useMemo<GuideContextValue>(
    () => ({
      segment: state?.segment ?? null,
      tourSeen,
      start,
      advance,
      skip,
    }),
    [state, tourSeen, start, advance, skip],
  );

  return (
    <GuideContext.Provider value={value}>{children}</GuideContext.Provider>
  );
}

export function useGuide(): GuideContextValue {
  return useContext(GuideContext);
}
