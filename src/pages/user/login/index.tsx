import { LockOutlined, MobileOutlined, UserOutlined } from '@ant-design/icons';
import {
  LoginForm,
  ProFormCaptcha,
  ProFormCheckbox,
  ProFormText,
} from '@ant-design/pro-components';
import {
  FormattedMessage,
  Helmet,
  history,
  useIntl,
  useModel,
} from '@umijs/max';
import { Alert, App, ConfigProvider, Tabs, theme } from 'antd';
import React, { useEffect, useState } from 'react';
import { flushSync } from 'react-dom';
import { login } from '@/services/ant-design-pro/api';
import { sendSmsCode } from '@/services/ant-design-pro/login';
import { loginErrorMessage } from '@/services/ant-design-pro/loginErrorPresentation.mjs';
import {
  apiMessage,
  isApiSuccess,
  localSmsCode,
} from '@/services/ant-design-pro/smsResponsePresentation.mjs';
import { STORAGE_KEYS, storage } from '@/utils/storage';
import Settings from '../../../../config/defaultSettings';
import styles from './index.module.less';

const LoginMessage: React.FC<{
  content: string;
}> = ({ content }) => {
  return (
    <Alert
      style={{
        marginBottom: 24,
      }}
      message={content}
      type="error"
      showIcon
    />
  );
};

const Login: React.FC = () => {
  const [userLoginState, setUserLoginState] = useState<API.LoginResult>({});
  const [loginErrorMsg, setLoginErrorMsg] = useState<string>('');
  const [type, setType] = useState<string>('account');
  const { initialState, setInitialState } = useModel('@@initialState');
  const { message } = App.useApp();
  const intl = useIntl();

  const handleShowcasePointer = (
    event: React.PointerEvent<HTMLElement>,
  ): void => {
    // 只对精细指针启用视差，避免影响触摸滚动和减少动态效果偏好。
    if (
      !window.matchMedia(
        '(hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference)',
      ).matches
    )
      return;
    const panel = event.currentTarget;
    const bounds = panel.getBoundingClientRect();
    const x = Math.max(
      0,
      Math.min(1, (event.clientX - bounds.left) / bounds.width),
    );
    const y = Math.max(
      0,
      Math.min(1, (event.clientY - bounds.top) / bounds.height),
    );
    panel.style.setProperty('--pointer-x', `${x * 100}%`);
    panel.style.setProperty('--pointer-y', `${y * 100}%`);
    panel.style.setProperty('--tilt-x', `${(0.5 - y) * 8}deg`);
    panel.style.setProperty('--tilt-y', `${(x - 0.5) * 10}deg`);
  };

  const resetShowcasePointer = (
    event: React.PointerEvent<HTMLElement>,
  ): void => {
    for (const property of [
      '--pointer-x',
      '--pointer-y',
      '--tilt-x',
      '--tilt-y',
    ]) {
      event.currentTarget.style.removeProperty(property);
    }
  };

  const fetchUserInfo = async () => {
    const userInfo = await initialState?.fetchUserInfo?.();
    if (userInfo) {
      flushSync(() => {
        setInitialState((s) => ({
          ...s,
          currentUser: userInfo,
        }));
      });
    }
  };

  /** 根路径默认进登录页；若本地已有有效 Token 则进入平台选择页 */
  useEffect(() => {
    const token = storage.get(STORAGE_KEYS.TOKEN);
    if (!token) return;
    (async () => {
      const user = await initialState?.fetchUserInfo?.();
      if (user) {
        flushSync(() => {
          setInitialState((s) => ({ ...s, currentUser: user }));
        });
        history.replace('/platforms');
      }
    })();
  }, []);

  const handleSubmit = async (values: API.LoginParams) => {
    try {
      const payload: API.LoginParams =
        type === 'mobile'
          ? {
              type,
              mobile: values.mobile,
              smsCode: values.captcha,
            }
          : {
              type,
              username: values.username,
              password: values.password,
            };
      // 登录
      // 登录失败由表单统一提示，避免全局处理器重复弹出网络错误。
      const msg = await login(payload, { skipErrorHandler: true });
      // 兼容两种成功标识：status === 'ok'（旧）或 code === 200（后端 code/msg/data）
      const isSuccess =
        (msg as any)?.status === 'ok' || (msg as any)?.code === 200;
      if (isSuccess) {
        setLoginErrorMsg('');
        setUserLoginState({});
        // 登录成功后把 token 写入本地（使用 utils/storage），供 request 拦截器读取
        const token =
          (msg as any)?.data?.token ??
          (msg as any)?.data?.accessToken ??
          (msg as any)?.token;
        if (token) {
          // 勾选自动登录 → localStorage；不勾选 → sessionStorage
          storage.set(STORAGE_KEYS.TOKEN, token, {
            persist: Boolean(values.autoLogin),
          });
        }
        const defaultLoginSuccessMessage = intl.formatMessage({
          id: 'pages.login.success',
          defaultMessage: '登录成功！',
        });
        message.success(defaultLoginSuccessMessage);
        await fetchUserInfo();
        const redirect = '/platforms';
        setTimeout(() => {
          history.push(redirect);
        }, 100);
        return;
      }
      console.log(msg);
      // 如果失败去设置用户错误信息
      setLoginErrorMsg(
        (msg as any)?.msg || (msg as any)?.message || '登录失败，请重试！',
      );
      setUserLoginState(msg);
    } catch (error) {
      console.log(error);
      const errMsg = loginErrorMessage(
        error,
        intl.formatMessage({
          id: 'pages.login.failure',
          defaultMessage: '登录失败，请重试！',
        }),
      );
      setLoginErrorMsg(errMsg);
      setUserLoginState({
        status: 'error',
        type,
      });
      message.error(errMsg);
    }
  };
  const { status, type: loginType } = userLoginState;

  return (
    <ConfigProvider
      theme={{
        algorithm: theme.defaultAlgorithm,
        token: { colorPrimary: '#3765d9' },
      }}
    >
      <main className={styles.page}>
        {/* header标题，来自umijs */}
        <Helmet>
          <title>
            {/* 国际化 locales文件夹*/}
            {intl.formatMessage({
              id: 'menu.login',
              defaultMessage: '登录页',
            })}
            {/* 全局标题配置，有则显示 */}
            {Settings.title && ` - ${Settings.title}`}
          </title>
        </Helmet>

        <section
          className={styles.showcase}
          aria-label="TSSAIPlatform AI 平台介绍"
          onPointerMove={handleShowcasePointer}
          onPointerLeave={resetShowcasePointer}
          onPointerCancel={resetShowcasePointer}
        >
          <div className={styles.showcaseHeader}>
            <div className={styles.brand}>
              <svg
                className={styles.platformMark}
                viewBox="0 0 330 96"
                role="img"
                aria-label="TSS AI Platform"
              >
                <rect
                  x="4"
                  y="3"
                  width="74"
                  height="73"
                  rx="5"
                  fill="#a9e1ec"
                  fillOpacity=".025"
                  stroke="#58758e"
                  strokeWidth="1.2"
                />
                <text
                  x="41"
                  y="58"
                  textAnchor="middle"
                  fill="#a9e1ec"
                  fontFamily="Georgia, serif"
                  fontSize="53"
                >
                  T
                </text>
                <text
                  x="99"
                  y="46"
                  fill="#f1f5ff"
                  fontFamily="Arial, Helvetica, sans-serif"
                  fontSize="43"
                  fontWeight="700"
                  letterSpacing="1"
                >
                  TSS
                </text>
                <text
                  x="215"
                  y="49"
                  fill="#9adce7"
                  fontFamily="Arial, Helvetica, sans-serif"
                  fontSize="45"
                  fontWeight="300"
                >
                  /
                </text>
                <text
                  x="101"
                  y="73"
                  fill="#b8cde7"
                  fontFamily="Arial, Helvetica, sans-serif"
                  fontSize="16"
                  fontWeight="700"
                  letterSpacing="4"
                >
                  AI PLATFORM
                </text>
              </svg>
            </div>
          </div>
          <div className={styles.story}>
            <div className={styles.eyebrow}>面向工业场景的 AI 工作空间</div>
            <h1>
              让数据成为洞察，
              <br />
              让模型走向应用<span>。</span>
            </h1>
            <p>
              连接数据、模型与算力。
              <br />
              在一个工作空间中，完成从实验到应用的每一步。
            </p>
          </div>
          <div className={styles.visual} aria-hidden="true">
            <svg viewBox="0 0 600 350" fill="none" aria-hidden="true">
              <defs>
                <linearGradient
                  id="tss-plane"
                  x1="140"
                  y1="50"
                  x2="480"
                  y2="270"
                  gradientUnits="userSpaceOnUse"
                >
                  <stop stopColor="#91c9ff" stopOpacity=".3" />
                  <stop offset="1" stopColor="#6489fa" stopOpacity=".03" />
                </linearGradient>
                <linearGradient
                  id="tss-line"
                  x1="100"
                  y1="180"
                  x2="500"
                  y2="180"
                  gradientUnits="userSpaceOnUse"
                >
                  <stop stopColor="#80d8ed" stopOpacity=".1" />
                  <stop offset=".5" stopColor="#94e4ed" />
                  <stop offset="1" stopColor="#8fa7ff" stopOpacity=".15" />
                </linearGradient>
              </defs>
              <path
                d="M30 225 300 70 570 225 300 380Z M30 175 300 20 570 175 M100 265 370 110 M170 305 440 150 M100 135 370 290 M170 95 440 250"
                stroke="#b5d4ff"
                strokeOpacity=".07"
              />
              <g className={styles.planes}>
                <path
                  d="m110 226 190-108 190 108-190 108Z"
                  fill="url(#tss-plane)"
                  stroke="#7698c6"
                  strokeOpacity=".4"
                />
                <path
                  d="m110 172 190-108 190 108-190 108Z"
                  fill="url(#tss-plane)"
                  stroke="#91c9ff"
                  strokeOpacity=".5"
                />
                <path
                  d="m110 118 190-108 190 108-190 108Z"
                  fill="url(#tss-plane)"
                  stroke="url(#tss-line)"
                />
                <path
                  d="M110 118v108M300 226v108M490 118v108"
                  stroke="#90baf3"
                  strokeOpacity=".35"
                  strokeDasharray="4 6"
                />
                <path
                  d="m186 119 114-65 114 65-114 65Z"
                  stroke="#95dfea"
                  strokeOpacity=".55"
                />
                <path
                  d="m238 119 62-35 62 35-62 35Z"
                  fill="#a1e7ef"
                  fillOpacity=".15"
                  stroke="#a1e7ef"
                />
                <path
                  d="m300 84 0 70m-62-35h124"
                  stroke="#a1e7ef"
                  strokeOpacity=".5"
                />
                <path
                  className={styles.dataStream}
                  d="m110 118 190-108 190 108-190 108Z M110 118v108l190 108 190-108V118"
                  stroke="#c1f2ff"
                  strokeWidth="2"
                  strokeDasharray="18 820"
                />
                <circle cx="110" cy="118" r="4" fill="#b3edee" />
                <circle cx="490" cy="172" r="4" fill="#9bb7ff" />
                <circle cx="300" cy="334" r="4" fill="#b3edee" />
              </g>
              <path
                d="M110 226H50M490 172h60"
                stroke="#91c9ff"
                strokeOpacity=".4"
              />
            </svg>
            <span className={styles.visualLabel}>数据 · 模型 · 算力</span>
          </div>
          <div className={styles.workflow}>
            <span>数据资产</span>
            <span aria-hidden="true">↗</span>
            <span>模型训练</span>
            <span aria-hidden="true">↗</span>
            <span>推理应用</span>
          </div>
          <div className={styles.showcaseFooter}>
            TSSAIPlatform · AI 工作空间<span>从数据出发，向应用延伸</span>
          </div>
        </section>
        <section className={styles.loginPanel} aria-label="登录">
          <div className={styles.panelTop}>
            统一 AI 工作空间<span>欢迎回来</span>
          </div>
          <div className={styles.formWrap}>
            <div className={styles.formHeading}>
              <div className={styles.formEyebrow}>YOUR AI WORKSPACE</div>
              <h2>登录工作空间</h2>
              <p>继续你的探索，让下一个想法成为现实。</p>
            </div>
            <LoginForm
              containerStyle={{
                padding: 0,
                minWidth: 0,
                height: 'auto',
                flex: 'none',
                overflow: 'visible',
              }}
              contentStyle={{ width: '100%', minWidth: 0 }}
              submitter={{
                searchConfig: { submitText: '登录工作空间' },
                submitButtonProps: {
                  icon: (
                    <span className={styles.buttonArrow} aria-hidden="true">
                      ↗
                    </span>
                  ),
                  iconPosition: 'end',
                },
              }}
              initialValues={{
                autoLogin: true,
              }}
              actions={[]}
              onFinish={async (values) => {
                await handleSubmit(values as API.LoginParams);
              }}
            >
              <Tabs
                activeKey={type}
                onChange={setType}
                items={[
                  {
                    key: 'account',
                    label: intl.formatMessage({
                      id: 'pages.login.accountLogin.tab',
                      defaultMessage: '账号密码登录',
                    }),
                  },
                  {
                    key: 'mobile',
                    label: intl.formatMessage({
                      id: 'pages.login.phoneLogin.tab',
                      defaultMessage: '手机验证码登录',
                    }),
                  },
                ]}
              />

              {status === 'error' && loginType === 'account' && (
                <LoginMessage
                  content={
                    loginErrorMsg ||
                    intl.formatMessage({
                      id: 'pages.login.accountLogin.errorMessage',
                      defaultMessage: '用户名/手机号或密码错误',
                    })
                  }
                />
              )}
              {type === 'account' && (
                <>
                  <ProFormText
                    name="username"
                    label="用户名或手机号"
                    fieldProps={{
                      size: 'large',
                      prefix: <UserOutlined />,
                      autoComplete: 'username',
                    }}
                    placeholder={intl.formatMessage({
                      id: 'pages.login.username.placeholder',
                      defaultMessage: '请输入用户名或手机号',
                    })}
                    rules={[
                      {
                        required: true,
                        message: (
                          <FormattedMessage
                            id="pages.login.username.required"
                            defaultMessage="请输入用户名或手机号！"
                          />
                        ),
                      },
                    ]}
                  />
                  <ProFormText.Password
                    name="password"
                    label="密码"
                    fieldProps={{
                      size: 'large',
                      prefix: <LockOutlined />,
                      autoComplete: 'current-password',
                    }}
                    placeholder={intl.formatMessage({
                      id: 'pages.login.password.placeholder',
                      defaultMessage: '请输入密码',
                    })}
                    rules={[
                      {
                        required: true,
                        message: (
                          <FormattedMessage
                            id="pages.login.password.required"
                            defaultMessage="请输入密码！"
                          />
                        ),
                      },
                    ]}
                  />
                </>
              )}

              {status === 'error' && loginType === 'mobile' && (
                <LoginMessage content={loginErrorMsg || '验证码错误'} />
              )}
              {type === 'mobile' && (
                <>
                  <ProFormText
                    fieldProps={{
                      size: 'large',
                      prefix: <MobileOutlined />,
                    }}
                    name="mobile"
                    label="手机号"
                    placeholder={intl.formatMessage({
                      id: 'pages.login.phoneNumber.placeholder',
                      defaultMessage: '手机号',
                    })}
                    rules={[
                      {
                        required: true,
                        message: (
                          <FormattedMessage
                            id="pages.login.phoneNumber.required"
                            defaultMessage="请输入手机号！"
                          />
                        ),
                      },
                      {
                        pattern: /^1[3-9]\d{9}$/,
                        message: (
                          <FormattedMessage
                            id="pages.login.phoneNumber.invalid"
                            defaultMessage="手机号格式错误！"
                          />
                        ),
                      },
                    ]}
                  />
                  <ProFormCaptcha
                    phoneName="mobile"
                    fieldProps={{
                      size: 'large',
                      prefix: <LockOutlined />,
                      maxLength: 6,
                    }}
                    captchaProps={{
                      size: 'large',
                    }}
                    placeholder={intl.formatMessage({
                      id: 'pages.login.captcha.placeholder',
                      defaultMessage: '请输入验证码',
                    })}
                    captchaTextRender={(timing, count) => {
                      if (timing) {
                        return `${count} ${intl.formatMessage({
                          id: 'pages.getCaptchaSecondText',
                          defaultMessage: '获取验证码',
                        })}`;
                      }
                      return intl.formatMessage({
                        id: 'pages.login.phoneLogin.getVerificationCode',
                        defaultMessage: '获取验证码',
                      });
                    }}
                    name="captcha"
                    label="验证码"
                    rules={[
                      {
                        required: true,
                        message: (
                          <FormattedMessage
                            id="pages.login.captcha.required"
                            defaultMessage="请输入验证码！"
                          />
                        ),
                      },
                      {
                        pattern: /^\d{6}$/,
                        message: '验证码为6位数字',
                      },
                    ]}
                    onGetCaptcha={async (phone) => {
                      if (!phone) {
                        message.error('请先输入正确的手机号');
                        return;
                      }
                      const result = await sendSmsCode({
                        phone,
                        purpose: 'LOGIN_REGISTER',
                      });
                      if (isApiSuccess(result)) {
                        const localCode = localSmsCode(result);
                        message.success(
                          localCode
                            ? `本地开发验证码：${localCode}`
                            : '验证码已发送到您的手机，请查收',
                        );
                      } else {
                        const errorMessage = apiMessage(
                          result,
                          '验证码发送失败，请重试',
                        );
                        message.error(errorMessage);
                        // ProFormCaptcha 只会在 Promise resolve 后开始倒计时。
                        throw new Error(errorMessage);
                      }
                    }}
                  />
                </>
              )}
              <div
                style={{
                  marginBottom: 24,
                }}
              >
                <ProFormCheckbox noStyle name="autoLogin">
                  <FormattedMessage
                    id="pages.login.rememberMe"
                    defaultMessage="自动登录"
                  />
                </ProFormCheckbox>
                <a
                  style={{
                    float: 'right',
                  }}
                  href="#/user/forgot-password"
                >
                  <FormattedMessage
                    id="pages.login.forgotPassword"
                    defaultMessage="忘记密码"
                  />
                </a>
              </div>
            </LoginForm>
            <div className={styles.registerPrompt}>
              <FormattedMessage
                id="pages.login.noAccount"
                defaultMessage="还没有账号？"
              />
              <a href="#/user/register" style={{ marginLeft: 8 }}>
                <FormattedMessage
                  id="pages.login.register"
                  defaultMessage="立即注册"
                />
              </a>
            </div>
          </div>
          <footer className={styles.panelFooter}>
            TSSAIPlatform · 数据标注与模型训练
          </footer>
        </section>
      </main>
    </ConfigProvider>
  );
};

export default Login;
