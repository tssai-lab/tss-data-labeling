// 在编辑器的 ShadowRoot 内统一分类选项外观，保留原有输入控件及键盘操作。
export const editorChoiceStyles = `
  .lsf-choices:not(.lsf-choices_layout_select) {
    display: flex; flex-wrap: wrap; align-items: center; gap: 8px;
  }
  .lsf-choices .lsf-choice {
    --choice-color: #2d9b43; --choice-fill: #d6ead7;
    display: inline-flex !important; width: auto; line-height: 1.4;
  }
  .lsf-choices .lsf-choice:nth-child(6n + 2) { --choice-color: #4945ed; --choice-fill: #dedbfc; }
  .lsf-choices .lsf-choice:nth-child(6n + 3) { --choice-color: #c08324; --choice-fill: #f8ebce; }
  .lsf-choices .lsf-choice:nth-child(6n + 4) { --choice-color: #218c91; --choice-fill: #d4eeee; }
  .lsf-choices .lsf-choice:nth-child(6n + 5) { --choice-color: #bd4e7f; --choice-fill: #f5dce7; }
  .lsf-choices .lsf-choice:nth-child(6n + 6) { --choice-color: #357ec0; --choice-fill: #d9e9f7; }
  .lsf-choices .lsf-choice__checkbox {
    position: relative; display: inline-flex; align-items: center; gap: 0;
    margin: 0; padding: 4px 9px 4px 7px; min-height: 30px; box-sizing: border-box;
    border: 1px solid transparent; border-left: 4px solid var(--choice-color);
    border-radius: 3px; background: var(--choice-fill); color: #19334a;
    font-size: 14px; line-height: 20px; transition: box-shadow .15s;
  }
  .lsf-choices .lsf-choice__checkbox > .ant-radio,
  .lsf-choices .lsf-choice__checkbox > .ant-checkbox {
    position: absolute; width: 1px; height: 1px; opacity: 0; overflow: hidden;
  }
  .lsf-choices .lsf-choice__checkbox:hover,
  .lsf-choices .lsf-choice__checkbox:focus-within {
    box-shadow: 0 0 0 2px var(--choice-color);
  }
  .lsf-choices .lsf-choice__checkbox.ant-radio-wrapper-checked,
  .lsf-choices .lsf-choice__checkbox.ant-checkbox-wrapper-checked {
    box-shadow: 0 0 0 2px var(--choice-color); font-weight: 600;
  }
  .lsf-choices .lsf-choice__checkbox.ant-radio-wrapper-checked::after,
  .lsf-choices .lsf-choice__checkbox.ant-checkbox-wrapper-checked::after {
    content: '✓'; position: static; display: inline; margin-left: 6px;
    width: auto; height: auto; visibility: visible; transform: none;
    color: var(--choice-color); border: 0; animation: none; opacity: 1;
  }
  .lsf-choices .lsf-choice__checkbox.ant-radio-wrapper-disabled,
  .lsf-choices .lsf-choice__checkbox.ant-checkbox-wrapper-disabled { opacity: .65; }
  .lsf-choices .lsf-hint { margin-left: 8px; font-size: 11px; color: #596d80; }
`;
