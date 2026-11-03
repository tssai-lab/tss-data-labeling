import type { ProLayoutProps } from '@ant-design/pro-components';

/**
 * @name
 */
const Settings: ProLayoutProps & {
  pwa?: boolean;
  logo?: string;
} = {
  navTheme: 'light',
  // 拂晓蓝
  colorPrimary: '#3765d9',
  layout: 'side',
  contentWidth: 'Fluid',
  fixedHeader: true,
  fixSiderbar: true,
  colorWeak: false,
  title: '东南大学 AI 平台',
  pwa: false,
  logo: '/seu-emblem-navy.png',
  iconfontUrl: '',
  token: {
    bgLayout: '#f3f6fb',
    header: {
      colorBgHeader: '#ffffffed',
      colorBgScrollHeader: '#ffffffed',
      colorHeaderTitle: '#172b45',
      colorTextRightActionsItem: '#40516b',
      colorBgRightActionsItemHover: '#eaf0fa',
      heightLayoutHeader: 64,
    },
    sider: {
      colorMenuBackground: '#101e35',
      colorBgMenuItemCollapsedElevated: '#172b45',
      colorBgMenuItemHover: '#203958',
      colorBgMenuItemSelected: '#294b75',
      colorTextMenu: '#b9cbe1',
      colorTextMenuSecondary: '#94abc7',
      colorTextMenuSelected: '#ffffff',
      colorTextMenuActive: '#ffffff',
      colorTextMenuItemHover: '#ffffff',
      colorTextMenuTitle: '#f1f5ff',
      colorTextSubMenuSelected: '#a9e1ec',
      colorBgCollapsedButton: '#203958',
      colorTextCollapsedButton: '#b9cbe1',
      colorTextCollapsedButtonHover: '#ffffff',
      menuHeight: 44,
    },
    pageContainer: {
      colorBgPageContainer: '#f3f6fb',
      colorBgPageContainerFixed: '#f3f6fb',
      paddingInlinePageContainerContent: 24,
      paddingBlockPageContainerContent: 24,
    },
  },
};

export default Settings;
