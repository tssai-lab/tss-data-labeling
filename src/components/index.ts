/**
 * 这个文件作为组件的目录
 * 目的是统一管理对外输出的组件，方便分类
 */
/**
 * 布局组件
 */

import CodeEditor from './CodeEditor';
import CodePreview from './CodePreview';
import Footer from './Footer';
/**
 * 公共业务组件
 */
import JsonEditor from './JsonEditor';
import { Question, SelectLang } from './RightContent';
import { AvatarDropdown, AvatarName } from './RightContent/AvatarDropdown';
import TrainingStatusBanner from './TrainingStatusBanner';

export {
  AvatarDropdown,
  AvatarName,
  CodeEditor,
  CodePreview,
  Footer,
  JsonEditor,
  Question,
  SelectLang,
  TrainingStatusBanner,
};
