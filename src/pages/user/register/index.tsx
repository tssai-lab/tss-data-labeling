import { LockOutlined, MobileOutlined, UserOutlined } from '@ant-design/icons';
import { FormattedMessage, Helmet, history, useIntl } from '@umijs/max';
import { Alert, App, Button, Form, Input, Radio } from 'antd';
import React, { useRef, useState } from 'react';
import {
  register as registerByUsername,
  registerByMobile,
} from '@/services/ant-design-pro/api';
import { sendSmsCode } from '@/services/ant-design-pro/login';
import {
  apiMessage,
  isApiSuccess,
  localSmsCode,
} from '@/services/ant-design-pro/smsResponsePresentation.mjs';
import Settings from '../../../../config/defaultSettings';

const RegisterMessage: React.FC<{
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

/**
 * 注册页
 */
const Register: React.FC = () => {
  const [form] = Form.useForm();
  const [registerState, setRegisterState] = useState<{
    status?: string;
    message?: string;
  }>({});
  const [countdown, setCountdown] = useState(0);
  const [registrationMode, setRegistrationMode] = useState<
    'username' | 'mobile'
  >('username');
  const countdownIntervalRef = useRef<NodeJS.Timeout | null>(null);
  const { message: messageApi } = App.useApp();
  const intl = useIntl();

  // 开始倒计时
  const startCountdown = () => {
    setCountdown(60);
    if (countdownIntervalRef.current) {
      clearInterval(countdownIntervalRef.current);
    }
    countdownIntervalRef.current = setInterval(() => {
      setCountdown((prev) => {
        if (prev <= 1) {
          if (countdownIntervalRef.current) {
            clearInterval(countdownIntervalRef.current);
          }
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
  };

  // 获取验证码
  const handleGetCaptcha = async () => {
    try {
      const phone = form.getFieldValue('phone');
      if (!phone) {
        messageApi.error('请输入手机号！');
        return;
      }

      // 验证手机号格式
      await form.validateFields(['phone']);

      const result = await sendSmsCode({
        phone: phone,
        purpose: 'LOGIN_REGISTER',
      });

      if (isApiSuccess(result)) {
        const localCode = localSmsCode(result);
        messageApi.success(
          localCode
            ? `本地开发验证码：${localCode}`
            : '验证码已发送到您的手机，请查收',
        );
        startCountdown();
      } else {
        const errorMessage = apiMessage(result, '验证码发送失败，请重试！');
        setRegisterState({
          status: 'error',
          message: errorMessage,
        });
        messageApi.error(errorMessage);
      }
    } catch (error: any) {
      if (error.errorFields) {
        // 表单验证错误
        return;
      }
      console.error('获取验证码失败:', error);
      setRegisterState({
        status: 'error',
        message: error.message || '验证码发送失败，请重试！',
      });
      messageApi.error(error.message || '验证码发送失败，请重试！');
    }
  };

  const handleSubmit = async (values: any) => {
    try {
      const body = {
        username: values.username,
        password: values.password,
        confirmPassword: values.confirmPassword,
        ...(registrationMode === 'mobile'
          ? { mobile: values.phone, smsCode: values.captcha }
          : {}),
      };
      // 用户名注册不依赖短信服务，手机号注册继续走原接口。
      const response = await (registrationMode === 'mobile'
        ? registerByMobile(body)
        : registerByUsername(body));

      if (response.code === 200) {
        messageApi.success('注册成功！');
        // 注册成功后跳转到登录页
        setTimeout(() => {
          history.push('/user/login');
        }, 1000);
      } else {
        const errorMessage = apiMessage(response, '注册失败，请重试！');
        setRegisterState({
          status: 'error',
          message: errorMessage,
        });
        messageApi.error(errorMessage);
      }
    } catch (error: any) {
      console.error('注册失败:', error);
      setRegisterState({
        status: 'error',
        message: error.message || '注册失败，请重试！',
      });
      messageApi.error(error.message || '注册失败，请重试！');
    }
  };

  const { status } = registerState;

  return (
    <div className="tss-auth-page">
      <Helmet>
        <title>
          {intl.formatMessage({
            id: 'menu.register',
            defaultMessage: '注册页',
          })}
          {Settings.title && ` - ${Settings.title}`}
        </title>
      </Helmet>

      <div
        style={{
          flex: '1',
          padding: '32px 0',
        }}
      >
        <div
          className="tss-auth-card"
          style={{
            margin: '0 auto',
            maxWidth: 400,
            padding: '24px',
            background: '#fff',
            borderRadius: '8px',
            boxShadow: '0 2px 8px rgba(0,0,0,0.1)',
          }}
        >
          <div style={{ textAlign: 'center', marginBottom: 24 }}>
            <div className="tss-auth-brand">
              <span className="tss-brand-mark" aria-hidden="true">
                T
              </span>
              <strong>TSS / AI PLATFORM</strong>
            </div>
            <h2 style={{ marginBottom: 8 }}>注册账号</h2>
            <p style={{ color: '#999', fontSize: 14 }}>
              {registrationMode === 'mobile'
                ? '设置用户名、手机号和登录密码'
                : '设置用户名和登录密码'}
            </p>
          </div>

          {status === 'error' && (
            <RegisterMessage
              content={registerState.message || '注册失败，请重试！'}
            />
          )}

          <Form
            form={form}
            onFinish={handleSubmit}
            layout="vertical"
            size="large"
          >
            <Form.Item label="注册方式">
              <Radio.Group
                value={registrationMode}
                buttonStyle="solid"
                onChange={(event) => setRegistrationMode(event.target.value)}
              >
                <Radio.Button value="username">用户名注册</Radio.Button>
                <Radio.Button value="mobile">手机号注册</Radio.Button>
              </Radio.Group>
            </Form.Item>

            <Form.Item
              name="username"
              rules={[
                { required: true, message: '请输入用户名' },
                { min: 6, max: 20, message: '用户名需为6-20位字符' },
                {
                  pattern: /^(?!1[3-9]\d{9}$).*$/,
                  message: '用户名不能使用手机号格式',
                },
              ]}
            >
              <Input
                prefix={<UserOutlined />}
                placeholder="设置用户名（6-20位，不能使用手机号）"
              />
            </Form.Item>

            <Form.Item
              name="password"
              rules={[
                { required: true, message: '请输入密码' },
                {
                  pattern: /^\w{6,16}$/,
                  message: '密码6-16位字母/数字/下划线',
                },
              ]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="请输入密码（至少6位）"
              />
            </Form.Item>

            <Form.Item
              name="confirmPassword"
              dependencies={['password']}
              rules={[
                { required: true, message: '请确认密码' },
                ({ getFieldValue }) => ({
                  validator(_, value) {
                    if (!value || getFieldValue('password') === value) {
                      return Promise.resolve();
                    }
                    return Promise.reject(new Error('两次输入的密码不一致'));
                  },
                }),
              ]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="请再次输入密码"
              />
            </Form.Item>

            {registrationMode === 'mobile' && (
              <>
                <Form.Item
                  name="phone"
                  preserve={false}
                  rules={[
                    { required: true, message: '请输入手机号！' },
                    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式错误！' },
                  ]}
                >
                  <Input
                    prefix={<MobileOutlined />}
                    placeholder="请输入手机号（用于验证码登录和找回密码）"
                    maxLength={11}
                  />
                </Form.Item>

                <Form.Item
                  name="captcha"
                  preserve={false}
                  rules={[
                    { required: true, message: '请输入验证码！' },
                    { pattern: /^\d{6}$/, message: '验证码为6位数字' },
                  ]}
                >
                  <Input
                    prefix={<LockOutlined />}
                    placeholder="请输入短信验证码"
                    maxLength={6}
                    suffix={
                      countdown > 0 ? (
                        <span style={{ color: '#999', fontSize: 12 }}>
                          {countdown}秒后可重新获取
                        </span>
                      ) : (
                        <Button
                          type="link"
                          size="small"
                          onClick={handleGetCaptcha}
                          style={{ padding: 0, height: 'auto' }}
                        >
                          获取验证码
                        </Button>
                      )
                    }
                  />
                </Form.Item>
              </>
            )}

            <Form.Item>
              <Button type="primary" htmlType="submit" block>
                注册账号
              </Button>
            </Form.Item>

            <Form.Item style={{ marginBottom: 0, textAlign: 'center' }}>
              <Button type="link" onClick={() => history.push('/user/login')}>
                已有账号？返回登录
              </Button>
            </Form.Item>
          </Form>
        </div>
      </div>
    </div>
  );
};

export default Register;
