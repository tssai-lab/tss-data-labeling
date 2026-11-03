import { Input } from 'antd';
import React from 'react';

const { TextArea } = Input;

export interface JsonEditorProps {
  defaultValue?: object;
  disabled?: boolean;
  onChange?: (value: string) => void;
  onValidate?: (isValid: boolean, error?: string) => void;
}

/**
 * JsonEditor 组件
 * 功能：支持 JSON 格式参数编辑，包含格式化、校验、默认示例、错误提示
 */
const JsonEditor: React.FC<JsonEditorProps> = ({
  defaultValue,
  disabled = false,
  onChange,
  onValidate,
}) => {
  const [value, setValue] = React.useState(
    defaultValue ? JSON.stringify(defaultValue, null, 2) : '',
  );
  const [error, setError] = React.useState<string>('');

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const newValue = e.target.value;
    setValue(newValue);

    // 校验 JSON 格式
    try {
      if (newValue.trim()) {
        JSON.parse(newValue);
        setError('');
        onValidate?.(true);
      } else {
        setError('');
        onValidate?.(false, '请输入JSON内容');
      }
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : 'JSON格式错误';
      setError(errorMsg);
      onValidate?.(false, errorMsg);
    }

    onChange?.(newValue);
  };

  const handleFormat = () => {
    try {
      const parsed = JSON.parse(value);
      const formatted = JSON.stringify(parsed, null, 2);
      setValue(formatted);
      setError('');
      onValidate?.(true);
      onChange?.(formatted);
    } catch (_err) {
      setError('无法格式化：JSON格式错误');
    }
  };

  return (
    <div>
      <TextArea
        value={value}
        onChange={handleChange}
        disabled={disabled}
        rows={10}
        placeholder='{"epochs": 10, "batch_size": 32}'
        style={{
          fontFamily: 'monospace',
          ...(error ? { borderColor: '#ff4d4f' } : {}),
        }}
      />
      {error && (
        <div style={{ color: '#ff4d4f', marginTop: 8, fontSize: 12 }}>
          {error}
        </div>
      )}
      <div style={{ marginTop: 8 }}>
        <button type="button" onClick={handleFormat} style={{ marginRight: 8 }}>
          格式化
        </button>
        <span style={{ fontSize: 12, color: '#999' }}>
          提示：输入有效的 JSON 格式
        </span>
      </div>
    </div>
  );
};

export default JsonEditor;
