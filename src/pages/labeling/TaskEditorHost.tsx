import { Alert } from 'antd';
import { forwardRef } from 'react';
import {
  resolveTaskTemplate,
  type TaskBinding,
} from '@/services/labelingTemplates.mjs';
import ClassificationEditorAdapter, {
  type ClassificationEditorHandle,
  type ClassificationEditorProps,
} from './ClassificationEditorAdapter';
import EnergyEditorAdapter, {
  type EnergyEditorProps,
} from './EnergyEditorAdapter';

// 仅登记随平台构建的可信组件，插件标识不能作为远程地址或动态导入表达式。
const editors = Object.freeze({
  'image-classification-editor': ClassificationEditorAdapter,
  'energy-station-day-editor': EnergyEditorAdapter,
});
export const ClassificationTaskEditor = forwardRef<
  ClassificationEditorHandle,
  ClassificationEditorProps & { binding?: TaskBinding }
>(function ClassificationTaskEditor({ binding, ...props }, ref) {
  try {
    const template = resolveTaskTemplate(binding);
    if (template.editorKey !== 'image-classification-editor')
      throw new Error('TEMPLATE_MISMATCH');
    const Editor = editors[template.editorKey];
    return <Editor {...props} ref={ref} />;
  } catch (error) {
    return (
      <Alert
        type="error"
        message={
          error instanceof Error ? error.message : '模板不兼容，已停止编辑'
        }
      />
    );
  }
});
export function EnergyTaskEditor({ binding, ...props }: EnergyEditorProps & { binding?: TaskBinding }) {
  try {
    resolveTaskTemplate(
      {
        templateId: 'energy-station-day',
        templateVersion: '1.0.0',
        ...binding,
        inputFormat: props.input.inputFormat,
        inputSchemaId: props.input.schemaId,
      },
      {
        preview: false,
      },
    );
    const Editor = editors['energy-station-day-editor'];
    return <Editor {...props} />;
  } catch (error) {
    return (
      <Alert
        type="error"
        message={
          error instanceof Error ? error.message : '模板不兼容，已停止编辑'
        }
      />
    );
  }
}
