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

// Only trusted, built components can enter this registry. Keys never become URLs/import expressions.
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
export function EnergyTaskEditor(props: EnergyEditorProps) {
  try {
    resolveTaskTemplate(
      {
        templateId: 'energy-station-day',
        templateVersion: '1.0.0',
        inputFormat: props.input.inputFormat,
        inputSchemaId: props.input.schemaId,
      },
      {
        preview:
          process.env.NODE_ENV !== 'production' &&
          process.env.LABELING_GAP_MOCK === 'true',
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
