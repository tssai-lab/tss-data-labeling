import { message } from 'antd';
import { SYSTEM_STATUS } from '@/constants/systemLabels';

type AccountLike = {
  id?: number;
  username?: string;
};

type CurrentUserLike =
  | {
      id?: number | string;
      userid?: string;
      username?: string;
      name?: string;
    }
  | null
  | undefined;

/** 列表行是否为当前登录账号（优先 id，其次用户名） */
export function isCurrentLoginAccount(
  record: AccountLike,
  currentUser: CurrentUserLike,
): boolean {
  if (!currentUser || record == null) return false;

  const currentId = Number(currentUser.id ?? currentUser.userid);
  if (
    Number.isFinite(currentId) &&
    currentId > 0 &&
    record.id != null &&
    Number(record.id) === currentId
  ) {
    return true;
  }

  const currentName = String(currentUser.username ?? currentUser.name ?? '')
    .trim()
    .replace(/（.*$/, '');
  const recordName = String(record.username ?? '').trim();
  return Boolean(currentName && recordName && currentName === recordName);
}

/** 禁止禁用当前登录账号；返回 false 表示已拦截 */
export function guardDisableCurrentAccount(
  record: AccountLike,
  newStatus: string,
  currentUser: CurrentUserLike,
): boolean {
  if (
    newStatus === SYSTEM_STATUS.DISABLED &&
    isCurrentLoginAccount(record, currentUser)
  ) {
    message.error('不能禁用当前登录账号');
    return false;
  }
  return true;
}

/** 禁止删除当前登录账号；返回 false 表示已拦截 */
export function guardDeleteCurrentAccount(
  record: AccountLike,
  currentUser: CurrentUserLike,
): boolean {
  if (isCurrentLoginAccount(record, currentUser)) {
    message.error('不能删除当前登录账号');
    return false;
  }
  return true;
}
