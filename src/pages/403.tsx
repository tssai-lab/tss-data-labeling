import { history } from '@umijs/max';
import { Button, Result } from 'antd';
import React from 'react';

/**
 * 403 无权限页面
 * 当用户无权限访问某页面（如手动输入 URL）时展示
 */
const NoAccessPage: React.FC = () => (
  <Result
    status="403"
    title="403"
    subTitle="无权限访问该页面"
    extra={
      <Button type="primary" onClick={() => history.push('/dashboard')}>
        返回首页
      </Button>
    }
  />
);

export default NoAccessPage;
