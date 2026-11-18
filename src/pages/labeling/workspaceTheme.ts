import type { ThemeConfig } from 'antd';
import type { CSSProperties } from 'react';

/** Shared tokens from the supplied V1.1 HTML, applied only to the labeling workspace. */
export const workspacePalette = {
  navy: '#102842',
  ink: '#182f46',
  muted: '#4c6276',
  blue: '#1e40af',
  blueHover: '#17338c',
  edge: '#d7e1eb',
  pale: '#eaf1ff',
  base: '#f1f5f8',
  surface: '#ffffff',
  rail: '#112d48',
  railActive: '#2a4d71',
  green: '#145b44',
  greenBg: '#e5f5ed',
  amber: '#8e4c14',
  amberBg: '#fff2d8',
  red: '#962f34',
  redBg: '#fceae8',
};

export const workspaceVariables = Object.fromEntries(
  Object.entries(workspacePalette).map(([name, value]) => [
    `--label-${name}`,
    value,
  ]),
) as CSSProperties;

export const workspaceTheme: ThemeConfig = {
  token: {
    colorPrimary: workspacePalette.blue,
    colorInfo: workspacePalette.blue,
    colorSuccess: workspacePalette.green,
    colorWarning: workspacePalette.amber,
    colorError: workspacePalette.red,
    colorText: workspacePalette.ink,
    colorTextSecondary: workspacePalette.muted,
    colorBgLayout: workspacePalette.base,
    colorBgContainer: workspacePalette.surface,
    colorBorder: workspacePalette.edge,
    colorBorderSecondary: workspacePalette.edge,
    borderRadius: 7,
    borderRadiusLG: 10,
    fontSize: 14,
    fontFamily:
      '"Fira Sans", "Noto Sans CJK SC", "Microsoft YaHei", system-ui, sans-serif',
    controlHeight: 37,
    controlHeightSM: 30,
  },
  components: {
    Card: {
      headerHeight: 47,
      headerHeightSM: 42,
      headerFontSize: 17,
      bodyPadding: 14,
      bodyPaddingSM: 12,
    },
    Table: {
      cellPaddingBlock: 11,
      cellPaddingInline: 10,
      cellPaddingBlockSM: 8,
      cellPaddingInlineSM: 8,
      headerBg: '#edf3f8',
      headerColor: workspacePalette.muted,
    },
    Button: { fontWeight: 600, primaryShadow: '0 2px 3px #17338c22' },
    Tabs: { horizontalItemGutter: 6, horizontalMargin: '0 0 14px 0' },
    Tag: { defaultBg: '#edf2f7', defaultColor: workspacePalette.muted },
  },
};
