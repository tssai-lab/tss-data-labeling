// @ts-ignore
/* eslint-disable */

 // 这段代码是 TypeScript 的全局类型声明，核心是定义前端调用后端 API 时的入参 / 出参数据结构；
 // 所有前端与后端交互的通用数据结构（接口入参、出参、通用错误格式等），都推荐集中写在这个文件里
declare namespace API {
  /** 角色：super_admin 超级管理员 | normal_admin 普通管理员 | user 普通用户 */
  type UserRole = 'super_admin' | 'normal_admin' | 'user';

  type CurrentUser = {
    name?: string;
    username?: string;
    id?: number | string;
    mobile?: string;
    roleId?: number;
    status?: boolean;
    avatar?: string;
    userid?: string;
    signature?: string;
    title?: string;
    group?: string;
    tags?: { key?: string; label?: string }[];
    notifyCount?: number;
    unreadCount?: number;
    country?: string;
    access?: string;
    /** 用于权限判断：super_admin | normal_admin | user */
    role?: UserRole;
    geographic?: {
      province?: { label?: string; key?: string };
      city?: { label?: string; key?: string };
    };
    address?: string;
    phone?: string;
  };

  type LoginResult = {
    code?: number;
    msg?: string;
    data?: {
      token?: string;
      accessToken?: string;
      userId?: number | string;
      username?: string;
      mobile?: string;
      roleId?: number;
      status?: boolean;
    };
    status?: string;
    type?: string;
    currentAuthority?: string;
  };

  type SmsCodeResult = {
    code?: number;
    status?: string;
    message?: string;
    msg?: string;
    data?: {
      /** 仅隔离的 local 开发模式返回；正式环境不存在。 */
      code?: string;
      expireSeconds?: number;
    };
  };

  type FakeCaptcha = SmsCodeResult;

  type LoginParams = {
    username?: string;
    password?: string;
    mobile?: string;
    smsCode?: string;
    captcha?: string;
    autoLogin?: boolean;
    type?: string;
  };

  type ErrorResponse = {
    /** 业务约定的错误码 */
    errorCode: string;
    /** 业务上的错误信息 */
    errorMessage?: string;
    /** 业务上的请求是否成功 */
    success?: boolean;
  };

  /** POST /api/user/forget/password — 与 ForgetPasswordDTO 一致 */
  type ForgotPasswordParams = {
    mobile?: string;
    smsCode?: string;
    newPassword?: string;
  };

  type ForgotPasswordResult = {
    code?: number;
    msg?: string;
    message?: string;
    data?: {
      captcha?: string;
      expireTime?: number;
    };
  };

  type ResetPasswordParams = {
    phone?: string;
    captcha?: string;
    newPassword?: string;
    confirmPassword?: string;
  };

  type ResetPasswordResult = {
    code?: number;
    msg?: string;
    data?: any;
  };

  type RegisterParams = {
    username?: string;
    password?: string;
    confirmPassword?: string;
    mobile?: string;
    smsCode?: string;
  };

  type RegisterResult = {
    code?: number;
    msg?: string;
    message?: string;
    data?: {
      userId?: string;
      username?: string;
      mobile?: string;
    };
  };
}
