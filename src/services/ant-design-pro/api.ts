// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取当前的用户 GET /api/user/current-user */
export async function currentUser(options?: { [key: string]: any }) {
  return request<{
    data: API.CurrentUser;
  }>('/user/current-user', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 退出登录接口 POST /api/user/logout */
export async function outLogin(options?: { [key: string]: any }) {
  return request<Record<string, any>>('/user/logout', {
    method: 'POST',
    ...(options || {}),
  });
}

/** 登录接口 POST /api/user/login */
export async function login(body: API.LoginParams, options?: { [key: string]: any }) {
  return request<API.LoginResult>('/user/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 忘记密码接口 POST /api/user/forget/password */
export async function forgotPassword(
  body: API.ForgotPasswordParams,
  options?: { [key: string]: any },
) {
  return request<API.ForgotPasswordResult>('/user/forget/password', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 重置密码接口 POST /api/user/reset-password */
export async function resetPassword(
  body: API.ResetPasswordParams,
  options?: { [key: string]: any },
) {
  return request<API.ResetPasswordResult>('/user/reset-password', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 注册接口 POST /api/user/register/username */
export async function register(
  body: API.RegisterParams,
  options?: { [key: string]: any },
) {
  return request<API.RegisterResult>('/user/register/username', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 手机号注册接口 POST /api/user/register/mobile */
export async function registerByMobile(
  body: API.RegisterParams,
  options?: { [key: string]: any },
) {
  return request<API.RegisterResult>('/user/register/mobile', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

