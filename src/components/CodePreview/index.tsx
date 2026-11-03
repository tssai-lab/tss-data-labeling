import { CopyOutlined, SaveOutlined, UndoOutlined } from '@ant-design/icons';
import { Alert, Button, Modal, message, Space } from 'antd';
import React, { useEffect, useState } from 'react';
import CodeEditor from '@/components/CodeEditor';

export interface CodePreviewProps {
  codeText?: string;
  /** 服务端原始内容，用于「恢复原始」 */
  originalCodeText?: string;
  fileName?: string;
  visible?: boolean;
  onClose?: () => void;
  /** 关闭弹窗时回传编辑后的内容（供详情页同步） */
  onContentChange?: (value: string) => void;
  /** 保存并发布（由详情页接入 V2 workspace）；可传入弹窗内最新草稿 */
  onSave?: (content?: string) => void;
  saving?: boolean;
  editable?: boolean;
}

/**
 * 代码预览弹窗：语法高亮 + 可编辑；保存由父页面接入 V2 工作区发布。
 */
const CodePreview: React.FC<CodePreviewProps> = ({
  codeText = '',
  originalCodeText,
  fileName = '',
  visible = false,
  onClose,
  onContentChange,
  onSave,
  saving = false,
  editable = true,
}) => {
  const [draft, setDraft] = useState(codeText);
  const baseline = originalCodeText ?? codeText;

  useEffect(() => {
    if (visible) {
      setDraft(codeText);
    }
  }, [visible, codeText]);

  const dirty = draft !== baseline;

  const handleCopy = () => {
    navigator.clipboard.writeText(draft).then(() => {
      message.success('代码已复制到剪贴板');
    });
  };

  const handleReset = () => {
    setDraft(baseline);
    message.info('已恢复为服务端原始内容');
  };

  const syncDraftToParent = () => {
    if (draft !== codeText) {
      onContentChange?.(draft);
    }
  };

  const handleClose = () => {
    syncDraftToParent();
    onClose?.();
  };

  const handleSave = () => {
    onContentChange?.(draft);
    onSave?.(draft);
  };

  return (
    <Modal
      title={`代码预览 - ${fileName}`}
      open={visible}
      onCancel={handleClose}
      footer={
        <Space wrap>
          {editable && onSave && (
            <Button
              type="primary"
              icon={<SaveOutlined />}
              loading={saving}
              disabled={!dirty}
              onClick={handleSave}
            >
              保存并发布
            </Button>
          )}
          {editable && dirty && (
            <Button icon={<UndoOutlined />} onClick={handleReset}>
              恢复原始
            </Button>
          )}
          <Button icon={<CopyOutlined />} onClick={handleCopy}>
            复制
          </Button>
          <Button onClick={handleClose}>关闭</Button>
        </Space>
      }
      width={900}
      destroyOnClose
    >
      <Alert
        type="info"
        showIcon
        message="可在此编辑源码。代码版本不可变：保存会发布为同一资产下的新版本。"
        style={{ marginBottom: 12 }}
      />
      <CodeEditor
        value={draft}
        fileName={fileName}
        onChange={editable ? setDraft : undefined}
        readOnly={!editable}
        minHeight="400px"
        maxHeight="560px"
      />
    </Modal>
  );
};

export default CodePreview;
