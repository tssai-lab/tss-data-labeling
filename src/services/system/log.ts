import { request } from '@umijs/max';
import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import qs from 'qs';
import { SYSTEM_API_CONFIG } from '@/constants/system';

dayjs.extend(utc);

/** 模块一统一响应 */
type Module1Result<T> = {
  code: number;
  message?: string;
  msg?: string;
  data?: T;
};

/** GET /api/system/log/list 响应 data */
type SystemLogListData = {
  list?: LogItem[];
  total?: number;
  pageNum?: number;
  pageSize?: number;
};

/** 类型字典项（/api/system/log/types） */
type LogTypeItem = {
  key?: string;
  label?: string;
};

/**
 * 页面 / ProTable 入参 → GET Query
 * 对齐：pageNum、pageSize、username、operateType、operateTime、ip、result、logType、content、currentUsername
 */
export interface LogListParams {
  pageNum?: number;
  pageSize?: number;
  username?: string;
  operateType?: string;
  /** 格式：yyyy-MM-dd HH:mm:ss */
  operateTime?: string[];
  ip?: string;
  result?: string;
  content?: string;
  logType?: string;
  /**
   * 个人中心传本人用户名；后端与 Token 校验后强制只查本人。
   * 日志管理页不要传。
   */
  currentUsername?: string;
}

/** 查询日志列表响应（兼容页面使用的 msg 字段） */
export interface LogListResponse {
  code: number;
  msg: string;
  data: {
    list: LogItem[];
    total: number;
    pageNum?: number;
    pageSize?: number;
  };
}

/** 页面展示用日志项（operateTime 已转为浏览器本地时区） */
export interface LogItem {
  id: number;
  username: string;
  operateType: string;
  /** 本地时区展示，格式 YYYY-MM-DD HH:mm:ss */
  operateTime: string;
  ip: string;
  content: string;
  result: 'success' | 'failed';
  logType?: string;
}

export interface CommonResponse<T = unknown> {
  code: number;
  msg: string;
  data?: T;
}

function getResultMessage(res?: Module1Result<unknown>): string {
  return res?.message ?? res?.msg ?? '';
}

/** 是否已带时区（Z 或 ±HH:mm / ±HHmm） */
function hasExplicitTimezone(value: string): boolean {
  return /([zZ]|[+-]\d{2}:?\d{2})$/.test(value.trim());
}

/**
 * 解析后端操作时间。
 * - 带 Z / 偏移：按 Instant 解析
 * - 无时区（如 2026-08-14 02:00:00）：按 **UTC** 解析
 *   （若当成本地解析，在东八区会少显示 8 小时）
 */
function parseBackendOperateTime(value: string) {
  const raw = value.trim();
  if (hasExplicitTimezone(raw)) {
    return dayjs(raw);
  }
  return dayjs.utc(raw.includes('T') ? raw : raw.replace(' ', 'T'));
}

/**
 * 后端 UTC 时间 → 浏览器本地时区展示（YYYY-MM-DD HH:mm:ss）。
 */
function formatOperateTime(value?: string): string {
  if (!value?.trim()) return '';
  const parsed = parseBackendOperateTime(value);
  if (!parsed.isValid()) return value.trim();
  return parsed.local().format('YYYY-MM-DD HH:mm:ss');
}

function normalizeResult(result?: string): 'success' | 'failed' {
  const s = (result ?? '').toLowerCase();
  if (s === 'success' || s === '成功') return 'success';
  return 'failed';
}

function mapLogItem(raw: Partial<LogItem>): LogItem {
  return {
    id: Number(raw.id ?? 0),
    username: raw.username ?? '',
    operateType: raw.operateType ?? '',
    operateTime: formatOperateTime(raw.operateTime),
    ip: raw.ip ?? '',
    content: raw.content ?? '',
    result: normalizeResult(raw.result),
    logType: raw.logType,
  };
}

/**
 * 筛选框本地墙钟时间 → 后端查询用 UTC（yyyy-MM-dd HH:mm:ss）。
 * 与 formatOperateTime 对称，避免列表已是本地时间、筛选却按本地字面量对 UTC 库。
 */
function toQueryDateTime(value?: string): string | undefined {
  if (!value?.trim()) return undefined;
  const raw = value.trim().replace('T', ' ').slice(0, 19);
  const local = dayjs(raw);
  if (!local.isValid()) return raw;
  return local.utc().format('YYYY-MM-DD HH:mm:ss');
}

function toSystemLogListQuery(
  params: LogListParams,
): Record<string, string | number | string[]> {
  const query: Record<string, string | number | string[]> = {
    pageNum: params.pageNum ?? 1,
    pageSize: params.pageSize ?? 10,
  };

  const username = params.username?.trim();
  if (username) query.username = username;

  const operateType = params.operateType?.trim();
  if (operateType) query.operateType = operateType;

  const ip = params.ip?.trim();
  if (ip) query.ip = ip;

  const result = params.result?.trim();
  if (result) query.result = result;

  const content = params.content?.trim();
  if (content) query.content = content;

  const logType = params.logType?.trim();
  if (logType) query.logType = logType;

  const currentUsername = params.currentUsername?.trim();
  if (currentUsername) query.currentUsername = currentUsername;

  if (params.operateTime?.length === 2) {
    const start = toQueryDateTime(params.operateTime[0]);
    const end = toQueryDateTime(params.operateTime[1]);
    if (start && end) {
      query.operateTime = [start, end];
    }
  }

  return query;
}

/** GET /api/system/log/types */
export async function getOperationTypes(options?: {
  [key: string]: unknown;
}): Promise<Record<string, string>> {
  try {
    const res = await request<
      Module1Result<Record<string, string> | LogTypeItem[]>
    >(SYSTEM_API_CONFIG.ENDPOINTS.LOG_OPERATION_TYPES, {
      method: 'GET',
      skipErrorHandler: true,
      ...(options || {}),
    });
    if (res.code !== 200 || !res.data) {
      return {};
    }
    if (Array.isArray(res.data)) {
      return Object.fromEntries(
        res.data
          .filter((item) => item.key)
          .map((item) => [String(item.key), String(item.label ?? item.key)]),
      );
    }
    return res.data;
  } catch {
    return {};
  }
}

/** ProTable valueEnum */
export async function getOperationTypeValueEnum(): Promise<
  Record<string, { text: string }>
> {
  const map = await getOperationTypes();
  return Object.fromEntries(
    Object.entries(map).map(([key, label]) => [key, { text: label }]),
  );
}

/** GET /api/system/log/list */
export async function querySystemLogs(
  params?: LogListParams,
  options?: { [key: string]: unknown },
) {
  return request<Module1Result<SystemLogListData>>(
    SYSTEM_API_CONFIG.ENDPOINTS.LOG_LIST,
    {
      method: 'GET',
      params: params
        ? toSystemLogListQuery(params)
        : { pageNum: 1, pageSize: 10 },
      // Spring List：operateTime=a&operateTime=b
      paramsSerializer: (p) => qs.stringify(p, { arrayFormat: 'repeat' }),
      ...(options || {}),
    },
  );
}

/**
 * 查询操作日志列表
 * - 主链路：GET /api/system/log/list
 * - 日志管理：不传 currentUsername
 * - 个人中心：传 currentUsername=本人
 */
export async function getLogList(
  params: LogListParams,
): Promise<LogListResponse> {
  const res = await querySystemLogs(params);
  const msg = getResultMessage(res);
  if (res.code !== 200) {
    return {
      code: res.code ?? 500,
      msg: msg || '查询失败',
      data: { list: [], total: 0 },
    };
  }
  const list = (res.data?.list ?? []).map((row) => mapLogItem(row));
  return {
    code: 200,
    msg: msg || 'ok',
    data: {
      list,
      total: res.data?.total ?? list.length,
      pageNum: res.data?.pageNum,
      pageSize: res.data?.pageSize,
    },
  };
}

/** GET /api/system/log/export（筛选条件同 list） */
export async function exportSystemLogs(
  params?: LogListParams,
  options?: { [key: string]: unknown },
): Promise<LogListResponse> {
  const query = params
    ? toSystemLogListQuery({
        ...params,
        pageNum: params.pageNum ?? 1,
        pageSize: params.pageSize ?? 10000,
      })
    : { pageNum: 1, pageSize: 10000 };
  const res = await request<Module1Result<SystemLogListData>>(
    SYSTEM_API_CONFIG.ENDPOINTS.LOG_EXPORT,
    {
      method: 'GET',
      params: query,
      paramsSerializer: (p) => qs.stringify(p, { arrayFormat: 'repeat' }),
      ...(options || {}),
    },
  );
  const msg = getResultMessage(res);
  if (res.code !== 200) {
    return {
      code: res.code ?? 500,
      msg: msg || '导出失败',
      data: { list: [], total: 0 },
    };
  }
  const list = (res.data?.list ?? []).map((row) => mapLogItem(row));
  return {
    code: 200,
    msg: msg || 'ok',
    data: {
      list,
      total: res.data?.total ?? list.length,
    },
  };
}

/** 将日志列表下载为 CSV */
export function downloadLogListAsCsv(
  list: LogItem[],
  filename = `operation-logs-${dayjsFilename()}.csv`,
) {
  const headers = [
    'username',
    'operateType',
    'operateTime',
    'ip',
    'content',
    'result',
  ];
  const escape = (value: unknown) => {
    const text = String(value ?? '');
    if (/[",\n\r]/.test(text)) {
      return `"${text.replace(/"/g, '""')}"`;
    }
    return text;
  };
  const lines = [
    headers.join(','),
    ...list.map((row) =>
      [
        row.username,
        row.operateType,
        row.operateTime,
        row.ip,
        row.content,
        row.result,
      ]
        .map(escape)
        .join(','),
    ),
  ];
  const blob = new Blob([`\uFEFF${lines.join('\n')}`], {
    type: 'text/csv;charset=utf-8;',
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

function dayjsFilename() {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`;
}

/** ProTable request 适配 */
export async function fetchLogList(
  params: LogListParams,
): Promise<LogListResponse> {
  return getLogList(params);
}

/** @deprecated 兼容旧导出名 */
export async function queryOperationLogs(
  params?: LogListParams,
  options?: { [key: string]: unknown },
) {
  return querySystemLogs(params, options);
}
