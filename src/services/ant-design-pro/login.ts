// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 发送真实或本地隔离环境验证码 POST /api/user/sms/code */
export async function sendSmsCode(
  params: {
    /** 手机号 */
    phone?: string;
    /** 登录/注册共用模板，找回密码使用重置密码模板。 */
    purpose?: 'LOGIN_REGISTER' | 'RESET_PASSWORD';
  },
  options?: { [key: string]: any },
) {
  return request<API.SmsCodeResult>('/user/sms/code', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: {
      mobile: params.phone,
      purpose: params.purpose ?? 'LOGIN_REGISTER',
    },
    skipErrorHandler: true,
    ...(options || {}),
  });
}

/** @deprecated 新代码请使用 sendSmsCode。 */
export const getFakeCaptcha = sendSmsCode;
