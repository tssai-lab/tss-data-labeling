import { Modal, Typography } from 'antd';
import React from 'react';

export function showTemporaryPasswordNotice(temporaryPassword?: string) {
  if (!temporaryPassword) {
    Modal.warning({
      title: '账号已创建',
      content:
        '服务端没有返回临时密码。请不要尝试公共默认密码，请通过登录页“忘记密码”完成密码设置。',
    });
    return;
  }

  Modal.success({
    title: '账号创建成功',
    content: (
      <div>
        <p>以下随机临时密码只显示这一次，请立即安全交给对应用户：</p>
        <Typography.Text copyable code strong>
          {temporaryPassword}
        </Typography.Text>
        <p style={{ marginTop: 12 }}>
          用户登录后应尽快修改密码；页面关闭后不能再次查询该明文。
        </p>
      </div>
    ),
  });
}
