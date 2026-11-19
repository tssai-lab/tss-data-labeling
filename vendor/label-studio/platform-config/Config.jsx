import { getDocsUrl } from '../libs/editor/src/utils/docs';
const recordLocalConfigAction = () => {};
import React, { useEffect, useMemo, useState, useRef } from "react";
import {translate} from './locale';
import CM from "codemirror";
import { Button, cnm } from "@humansignal/ui";
import { IconTrash, IconInfoOutline } from "@humansignal/icons";
import { ToggleItems } from "./ToggleItems";
import { Form, Input } from "./Bridge";
import { useAPI } from "./Bridge";
import { cn } from "../libs/editor/src/utils/bem";
import { Palette } from "./palette";
const FF_UNSAVED_CHANGES = "platform-leave"; const isFF = () => true;
import { colorNames } from "./colors";
import "./Config.scss";
import { Preview } from "./Preview";
import { ff, LARGE_CONFIG_MESSAGE, LARGE_CONFIG_TAG_THRESHOLD, countConfigTags } from "@humansignal/core";
import { DEFAULT_COLUMN, EMPTY_CONFIG, isEmptyConfig, Template } from "./Template";
import { TemplatesList } from "./TemplatesList";

import tags from "@humansignal/core/lib/utils/schema/tags.json";
import { UnsavedChanges } from "./Bridge";
import { Checkbox, CodeEditor, Select } from "@humansignal/ui";
import { snakeCase } from "@humansignal/core";
import { useConfigResizer } from "./useConfigResizer";
import { EditorResizer } from "./EditorResizer";

const wizardClass = cn("wizard");
const configClass = cn("configure");

const hasEmptyChoices = (xml) => {
  if (!xml || typeof DOMParser === "undefined") return false;

  const document = new DOMParser().parseFromString(xml, "application/xml");
  if (document.querySelector("parsererror")) return false;

  return Array.from(document.getElementsByTagName("Choices")).some((choices) =>
    !Array.from(choices.children).some((choice) => choice.tagName === "Choice"),
  );
};

const withPreviewChoices = (xml) => {
  if (!xml || typeof DOMParser === "undefined") return xml;

  const document = new DOMParser().parseFromString(xml, "application/xml");
  if (document.querySelector("parsererror")) return xml;

  let changed = false;
  for (const choices of document.getElementsByTagName("Choices")) {
    if (![...choices.children].some((choice) => choice.tagName === "Choice")) {
      const choice = document.createElement("Choice");
      choice.setAttribute("value", "预览选项");
      choices.appendChild(choice);
      changed = true;
    }
  }

  return changed ? new XMLSerializer().serializeToString(document) : xml;
};

/**
 * AdaptivePreview - Shows manual update banner for large configs,
 * normal auto-updating preview for smaller configs.
 *
 * When FF_PREVIEW_PERFORMANCE is enabled and config has >= 200 tags,
 * shows a banner with "更新预览" button instead of auto-updating.
 *
 * Controlled by FF_PREVIEW_PERFORMANCE feature flag.
 *
 * Wrapped in React.memo to prevent unnecessary re-renders when parent re-renders.
 */
const AdaptivePreview = React.memo(({ config, hasPendingUpdate, onUpdatePreview, isUpdating, ...previewProps }) => {
  const isFeatureEnabled = ff.isActive(ff.FF_PREVIEW_PERFORMANCE);

  // Memoize tag count calculation to avoid re-computing on every render
  const tagCount = useMemo(() => countConfigTags(config || ""), [config]);
  const isLargeConfig = tagCount >= LARGE_CONFIG_TAG_THRESHOLD;

  // Only show manual update banner when FF is ON and config is large and there are pending updates
  const showManualUpdateBanner = isFeatureEnabled && isLargeConfig && hasPendingUpdate;

  if (showManualUpdateBanner) {
    return (
      <div className={configClass.elem("preview-container").toClassName()}>
        <div className={configClass.elem("preview-info-banner").toClassName()}>
          <IconInfoOutline width={16} height={16} />
          <span>{LARGE_CONFIG_MESSAGE}</span>
          <Button size="small" onClick={onUpdatePreview} waiting={isUpdating} disabled={isUpdating}>
            {isUpdating ? "正在更新…" : "更新预览"}
          </Button>
        </div>
        <Preview config={config} {...previewProps} />
      </div>
    );
  }

  return <Preview config={config} {...previewProps} />;
});

const EmptyConfigPlaceholder = () => (
  <div className={configClass.elem("empty-config").toClassName()}>
    <p>当前配置为空，请选择模板或编写 XML 后预览。</p>
    <p>
      从预设模板开始，或在 XML 代码面板编写配置。配置使用
      XML，您可以{" "}
      <a href={getDocsUrl("tags/")} target="_blank" rel="noreferrer">
        查看支持的控件文档
      </a>
      .
    </p>
  </div>
);

const Label = ({ label, template, color }) => {
  const value = label.getAttribute("value");

  return (
    <li
      className={cnm(
        configClass
          .elem("label")
          .mod({ choice: label.tagName === "Choice" })
          .toClassName(),
        "group",
      )}
    >
      <span className={cnm(configClass.elem("label-text").toClassName(), "flex")}>
        <label style={{ background: color }}>
          <Input
            type="color"
            className={configClass.elem("label-color").toClassName()}
            value={colorNames[color] || color}
            onChange={(e) => template.changeLabel(label, { background: e.target.value })}
          />
        </label>
        <Input aria-label="标签名称" value={value} onChange={(e)=>template.changeLabel(label,{value:e.target.value})}/>
      </span>
      <Button
        type="button"
        look="string"
        size="smaller"
        variant="negative"
        onClick={() => template.removeLabel(label)}
        aria-label="删除标签"
        className="hidden !p-0 z-10 absolute right-0 [&_span]:!p-0 group-hover:inline-flex"
        leading={<IconTrash className="w-4 h-4 fill-[currentColor]" />}
      />
    </li>
  );
};

const ConfigureControl = ({ control, template }) => {
  const refLabels = React.useRef();
  const [, refreshLabels] = React.useReducer((version) => version + 1, 0);
  const [addError, setAddError] = React.useState("");
  const tagname = control.tagName;

  if (tagname !== "Choices" && !tagname.endsWith("Labels")) return null;
  const palette = Palette();

  const onAddLabels = () => {
    if (!refLabels.current) return;
    const input = refLabels.current.value;
    if (!input.trim()) return;
    try {
      template.addLabels(control, input);
      setAddError("");
      // 控件节点由 Template 直接修改，主动刷新以同步标签数量和列表。
      refreshLabels();
    } catch (error) {
      setAddError(error instanceof Error ? error.message : "添加标签失败，请重试。");
      return;
    }
    refLabels.current.value = "";
  };
  const onKeyPress = (e) => {
    if (e.key === "Enter" && e.ctrlKey) {
      e.preventDefault();
      onAddLabels();
    }
  };

  return (
    <div className={configClass.elem("labels").toClassName()}>
      <form className={configClass.elem("add-labels").toClassName()} onSubmit={(e)=>e.preventDefault()}>
        <h4>{tagname === "Choices" ? "添加分类选项" : "添加区域标签"}</h4>
        <span>每行一个标签，Ctrl＋Enter 批量添加</span>
        <textarea
          name="labels"
          id=""
          cols="50"
          rows="5"
          ref={refLabels}
          onKeyPress={onKeyPress}
          className="lsf-textarea-ls p-2 px-3"
        />
        {addError && <p role="alert">{addError}</p>}
        <Button
          type="button"
          size="medium"
          variant="primary"
          look="filled"
          onClick={onAddLabels}
          className="labeling-add-labels-button"
          aria-label="添加标签"
        >
          <span className="labeling-add-labels-icon" aria-hidden="true">+</span>
          添加标签
        </Button>
      </form>
      <div className={configClass.elem("current-labels").toClassName()}>
        <h3>
          {tagname === "Choices" ? "分类选项" : "区域标签"} ({control.children.length})
        </h3>
        {tagname === "Choices" && control.children.length === 0 && (
          <p className="text-sm text-amber-700">
            请至少添加一个分类选项，配置完成后才能预览和标注。
          </p>
        )}
        <ul>
          {Array.from(control.children).map((label) => (
            <Label
              label={label}
              template={template}
              key={label.getAttribute("alias") || label.getAttribute("value")}
              color={label.getAttribute("background") || palette.next().value}
            />
          ))}
        </ul>
      </div>
    </div>
  );
};

const ConfigureSettings = ({ template }) => {
  const { settings } = template;

  if (!settings) return null;
  const keys = Object.keys(settings);

  const items = keys.map((key) => {
    const options = settings[key];
    const type = Array.isArray(options.type) ? Array : options.type;
    const $object = options.object;
    const $tag = options.control ? options.control : $object;

    if (!$tag) return null;
    if (options.when && !options.when($tag)) return;
    let value = false;

    if (options.value) value = options.value($tag);
    else if (typeof options.param === "string") value = $tag.getAttribute(options.param);
    if (value === "true") value = true;
    if (value === "false") value = false;
    let onChange;
    let size;

    switch (type) {
      case Array:
        onChange = (val) => {
          if (typeof options.param === "function") {
            options.param($tag, val);
          } else {
            $object.setAttribute(options.param, val);
          }
          template.render();
        };
        return (
          <li key={key}>
            <Select
              triggerClassName="border"
              value={value}
              onChange={onChange}
              options={options.type.map(value=>({value,label:translate(value)}))}
              label={translate(options.title)}
              isInline={true}
              dataTestid={`select-trigger-${options.title.replace(/\s+/g, "-").replace(":", "").toLowerCase()}-${value}`}
            />
          </li>
        );
      case Boolean:
        onChange = (e) => {
          if (typeof options.param === "function") {
            options.param($tag, e.target.checked);
          } else {
            $object.setAttribute(options.param, e.target.checked ? "true" : "false");
          }
          template.render();
        };
        return (
          <li key={key}>
            <Checkbox checked={value} onChange={onChange}>
              {translate(options.title)}
            </Checkbox>
          </li>
        );
      case String:
      case Number:
        size = options.type === Number ? 5 : undefined;
        onChange = (e) => {
          if (typeof options.param === "function") {
            options.param($tag, e.target.value);
          } else {
            $object.setAttribute(options.param, e.target.value);
          }
          template.render();
        };
        return (
          <li key={key}>
            <label>
              {translate(options.title)} <Input type="text" onInput={onChange} value={value} size={size} />
            </label>
          </li>
        );
    }
  });

  // check for active settings
  if (!items.filter(Boolean).length) return null;

  return (
    <ul className={configClass.elem("settings").toClassName()}>
      <li>
        <h4>控件设置</h4>
        <ul className={configClass.elem("object-settings").toClassName()}>{items}</ul>
      </li>
    </ul>
  );
};

const FIELD_SOURCE_OPTION_STYLE = {
  width: "100%",
  boxSizing: "border-box",
  minHeight: 42,
  margin: "3px 0",
  padding: 4,
  border: "1px solid #e4eaf3",
  borderRadius: 8,
  backgroundColor: "#f7f9fc",
  color: "#29445f",
  fontWeight: 500,
};

// configure value source for `obj` object tag
const ConfigureColumn = ({ template, obj, columns }) => {
  const valueAttr = obj.hasAttribute("valueList") ? "valueList" : "value";
  const value = obj.getAttribute(valueAttr)?.replace(/^\$/, "");
  // if there is a value set already and it's not in the columns
  // or data was not uploaded yet
  const [isManual, setIsManual] = useState(!!value && !columns?.includes(value));
  // value is stored in state to make input conrollable
  // changes will be sent by Enter and blur
  const [newValue, setNewValue] = useState(`$${value}`);

  // update local state when external value changes
  useEffect(() => {
    setNewValue(`$${value}`);
  }, [value]);

  const updateValue = (value) => {
    const newValue = value.replace(/^\$/, "");

    obj.setAttribute(valueAttr, `$${newValue}`);
    template.render();
  };

  const selectValue = (value) => {
    if (value === "-") {
      setIsManual(true);
      return;
    }
    if (isManual) {
      setIsManual(false);
    }

    updateValue(value);
  };

  const handleChange = (e) => {
    const newValue = e.target.value.replace(/^\$/, "");

    setNewValue(`$${newValue}`);
  };

  const handleBlur = () => {
    updateValue(newValue);
  };

  const handleKeyDown = (e) => {
    if (e.key === "Enter") {
      e.preventDefault();
      updateValue(e.target.value);
    }
  };

  const options = useMemo(() => {
    const columnOptions =
      columns?.map((column) => ({
        value: column,
        label: column === DEFAULT_COLUMN ? "导入数据" : `$${column}`,
        className: "labeling-field-source-option",
        style: FIELD_SOURCE_OPTION_STYLE,
      })) ?? [];
    if (!columns?.length) {
      columnOptions.push({
        value,
        label: "导入数据",
        className: "labeling-field-source-option",
        style: FIELD_SOURCE_OPTION_STYLE,
      });
    }
    columnOptions.push({
      value: "-",
      label: "手动设置",
      className: "labeling-field-source-option",
      style: FIELD_SOURCE_OPTION_STYLE,
    });
    return columnOptions;
  }, [columns, value]);

  return (
    <p>
      数据对象 {obj.tagName}
      {template.objects.length > 1 && ` for ${obj.getAttribute("name")}`}
      {" 读取自 "}
      {columns?.length > 0 && columns[0] !== DEFAULT_COLUMN && "字段 "}
      <Select
        triggerClassName="labeling-field-source-trigger"
        contentClassName="labeling-field-source-options"
        contentStyle={{ width: "var(--radix-popover-trigger-width)", minWidth: "var(--radix-popover-trigger-width)" }}
        onChange={selectValue}
        value={isManual ? "-" : value}
        options={options}
        isInline={true}
        dataTestid={`select-trigger-use-${obj.tagName.toLowerCase().replace(/\s/g, "-")}-from-field-${isManual ? "-" : value}`}
      />
      {isManual && <Input value={newValue} onChange={handleChange} onBlur={handleBlur} onKeyDown={handleKeyDown} />}
    </p>
  );
};

const ConfigureColumns = ({ columns, template }) => {
  if (!template.objects.length) return null;

  return (
    <div className={configClass.elem("object").toClassName()}>
      <h4>数据字段绑定</h4>
      {template.objects.length > 1 && columns?.length > 0 && columns.length < template.objects.length && (
        <p className={configClass.elem("object-error").toClassName()}>
          当前样本字段不足以匹配模板
        </p>
      )}
      {columns?.length === 0 && (
        <p className={configClass.elem("object-error").toClassName()}>
          选择数据字段前请先提供样本，或者在 XML 代码中
          指定字段。
        </p>
      )}
      {template.objects.map((obj) => (
        <ConfigureColumn key={obj.getAttribute("name")} {...{ obj, template, columns }} />
      ))}
    </div>
  );
};

const Configurator = ({
  columns,
  config,
  project,
  template,
  setTemplate,
  onBrowse,
  onSaveClick,
  onValidate,
  disableSaveButton,
  warning,
  hasChanges,
}) => {
  const [configure, setConfigure] = React.useState(isEmptyConfig(config) ? "code" : "visual");
  const [visualLoaded, loadVisual] = React.useState(configure === "visual");
  const [waiting, setWaiting] = React.useState(false);
  const [saved, setSaved] = React.useState(false);
  const containerRef = useRef(null);
  const [containerWidth, setContainerWidth] = useState(undefined);

  // Resizer hook
  const { editorWidthPixels, setEditorWidthPixels, constraints } = useConfigResizer({
    projectId: project?.id,
    containerWidth,
  });

  // Track container width for resizer constraints
  // Observes container dimensions to provide the resizer hook with container width for calculating valid min/max bounds
  useEffect(() => {
    if (!containerRef.current) return;

    let rafId;
    const updateWidth = () => {
      rafId && cancelAnimationFrame(rafId);
      rafId = requestAnimationFrame(() => {
        if (containerRef.current) {
          setContainerWidth(containerRef.current.clientWidth);
        }
      });
    };

    const resizeObserver = new ResizeObserver(updateWidth);
    resizeObserver.observe(containerRef.current);

    updateWidth();

    return () => {
      rafId && cancelAnimationFrame(rafId);
      resizeObserver.disconnect();
    };
  }, []);

  // config update is debounced because of user input
  const [configToCheck, setConfigToCheck] = React.useState();
  // then we wait for validation and sample data for this config
  const [error, setError] = React.useState();
  const [parserError, setParserError] = React.useState();
  const [data, setData] = React.useState();
  const [loading, setLoading] = useState(false);
  // and only with them we'll update config in preview
  const [configToDisplay, setConfigToDisplay] = React.useState(config);
  // Track if we're in manual update mode (for large configs)
  // Once enabled, stays enabled until user clicks "更新预览"
  const [manualUpdateMode, setManualUpdateMode] = React.useState(false);
  // Track if config has changed since last preview update
  const [hasPendingChanges, setHasPendingChanges] = React.useState(false);
  // Track the last config that was successfully validated and displayed
  const lastValidatedConfig = React.useRef(null);

  const debounceTimer = React.useRef();
  const api = useAPI();
  const isFeatureEnabled = ff.isActive(ff.FF_PREVIEW_PERFORMANCE);

  React.useEffect(() => {
    const tagCount = countConfigTags(config);
    const isLargeConfig = tagCount >= LARGE_CONFIG_TAG_THRESHOLD;
    const hasCompletedFirstValidation = lastValidatedConfig.current !== null;

    // Always validate if we haven't completed the first validation yet
    // This ensures the preview shows something on first load
    if (!hasCompletedFirstValidation) {
      // Enter manual mode for large configs, but still do the initial validation
      if (isLargeConfig && isFeatureEnabled) {
        setManualUpdateMode(true);
      }
      debounceTimer.current = window.setTimeout(() => {
        setConfigToCheck(config);
      }, 300);
      return () => window.clearTimeout(debounceTimer.current);
    }

    // After first validation: if we're in manual mode, just mark pending changes
    if (manualUpdateMode) {
      setHasPendingChanges(true);
      return;
    }

    // Enter manual mode for large configs when feature flag is enabled
    if (isLargeConfig && isFeatureEnabled) {
      setManualUpdateMode(true);
      setHasPendingChanges(true);
      return;
    }

    // Normal debounced auto-update for small configs or when feature flag is off
    debounceTimer.current = window.setTimeout(() => {
      setConfigToCheck(config);
    }, 300);

    return () => window.clearTimeout(debounceTimer.current);
  }, [config, manualUpdateMode, isFeatureEnabled]);

  // Handler for manual preview update (used for large configs)
  const handleManualUpdate = React.useCallback(() => {
    // Check if we should stay in manual mode after this update
    const tagCount = countConfigTags(config);
    const isLargeConfig = tagCount >= LARGE_CONFIG_TAG_THRESHOLD;

    // If still large, stay in manual mode but clear pending changes
    // If now small, exit manual mode
    setManualUpdateMode(isLargeConfig && isFeatureEnabled);
    setHasPendingChanges(false);
    setConfigToCheck(config);
  }, [config, isFeatureEnabled]);

  React.useEffect(() => {
    let active = true;
    const validate = async () => {
      if (!configToCheck) return;

      // 空分类仅在预览副本中补充临时选项，编辑器和待保存配置保持不变。
      const previewConfig = hasEmptyChoices(configToCheck)
        ? withPreviewChoices(configToCheck)
        : configToCheck;

      setLoading(true);

      const validation = await api.callApi("validateConfig", {
        params: { pk: project.id },
        body: { label_config: previewConfig },
        errorFilter: () => true,
      });
      if (!active) return;

      if (validation?.error) {
        setError(validation.response);
        setLoading(false);
        return;
      }

      setError(null);
      onValidate?.(validation);

      const sample = await api.callApi("createSampleTask", {
        params: { pk: project.id },
        body: { label_config: previewConfig },
        errorFilter: () => true,
      });
      if (!active) return;

      setLoading(false);
      if (sample && !sample.error) {
        setData(sample.sample_task);
        setConfigToDisplay(previewConfig);
        // Track that we've completed a successful validation
        lastValidatedConfig.current = configToCheck;
      } else {
        // @todo validation can be done in this place,
        // @todo but for now it's extremely slow in /sample-task endpoint
        setError(sample?.response);
      }
    };
    validate();
    return () => { active = false; };
  }, [configToCheck]);

  // code should be reloaded on every render because of uncontrolled codemirror
  // visuals should be always rendered after first render
  // so load it on the first access, then just show/hide
  const onSelect = (value) => {
    setConfigure(value);
    if (value === "visual") loadVisual(true);
  };

  const onChange = React.useCallback(
    (config) => {
      try {
        setParserError(null);
        setTemplate(config);
      } catch (e) {
        setParserError({
          detail: "XML 解析错误",
          validation_errors: [e.message],
        });
      }
    },
    [setTemplate],
  );

  const onSave = async () => {
    setError(null);
    setWaiting(true);
    const res = await onSaveClick();

    setWaiting(false);

    if (res === true) {
      setSaved(true);
      setTimeout(() => setSaved(false), 1500);
    } else {
      setError(res);
    }
    return res;
  };

  function completeAfter(cm, pred) {
    if (!pred || pred()) {
      setTimeout(() => {
        if (!cm.state.completionActive) cm.showHint({ completeSingle: false });
      }, 100);
    }
    return CM.Pass;
  }

  function completeIfInTag(cm) {
    return completeAfter(cm, () => {
      const token = cm.getTokenAt(cm.getCursor());

      if (token.type === "string" && (!/['"]$/.test(token.string) || token.string.length === 1)) return false;

      const inner = CM.innerMode(cm.getMode(), token.state).state;

      return inner.tagName;
    });
  }

  // Memoize error to prevent AdaptivePreview re-renders when error hasn't changed
  const previewError = useMemo(
    () => parserError || error || (configure === "code" && warning) || null,
    [parserError, error, configure, warning],
  );

  const extra = (
    <p className={configClass.elem("tags-link").toClassName()}>
      使用 XML 控件配置标注界面。&nbsp;
      <a href={getDocsUrl("tags/")} target="_blank" rel="noreferrer">
        查看控件文档
      </a>
      .
    </p>
  );

  return (
    <div className={configClass}>
      <div
        className={configClass.elem("container").toClassName()}
        ref={containerRef}
        style={{
          gridTemplateColumns: `${editorWidthPixels}px minmax(516px, 1fr)`,
        }}
      >
        <div className="flex flex-col">
          <h1>标注界面{hasChanges ? " *" : ""}</h1>
          <header>
            <Button
              type="button"
              data-leave={true}
              onClick={onBrowse}
              size="medium"
              look="outlined"
              className="labeling-template-browser"
              aria-label="浏览模板"
            >
              浏览模板
            </Button>
            <ToggleItems items={{ code: "XML 代码", visual: "可视化" }} active={configure} onSelect={onSelect} />
          </header>
          <div className={configClass.elem("editor").toClassName()}>
            {configure === "code" && (
              <div className={cnm(configClass.elem("code").toClassName(), configure !== "code" ? "!hidden" : "")}>
                <CodeEditor
                  name="code"
                  id="edit_code"
                  value={config}
                  autoCloseTags={true}
                  smartIndent={true}
                  detach
                  border
                  extensions={["hint", "xml-hint"]}
                  options={{
                    mode: "xml",
                    theme: "default",
                    lineNumbers: true,
                    extraKeys: {
                      "'<'": completeAfter,
                      // "'/'": completeIfAfterLt,
                      "' '": completeIfInTag,
                      "'='": completeIfInTag,
                      "Ctrl-Space": "autocomplete",
                      "Ctrl-F": "findPersistent",
                      "Cmd-F": "findPersistent",
                    },
                    hintOptions: { schemaInfo: tags, container: containerRef.current },
                  }}
                  // don't close modal with Escape while editing config
                  onKeyDown={(_editor, e) => {
                    if (e.code === "Escape") e.stopPropagation();
                  }}
                  onChange={(_editor, _data, value) => onChange(value)}
                />
              </div>
            )}
            {visualLoaded && (
              <div className={cnm(configClass.elem("visual").toClassName(), configure !== "visual" ? "!hidden" : "")}>
                {isEmptyConfig(config) && <EmptyConfigPlaceholder />}
                <ConfigureColumns columns={columns} project={project} template={template} />
                {template.controls.map((control) => (
                  <ConfigureControl control={control} template={template} key={control.getAttribute("name")} />
                ))}
                <ConfigureSettings template={template} />
              </div>
            )}
          </div>
          {disableSaveButton !== true && onSaveClick && (
            <Form.Actions size="small" extra={configure === "code" && extra} valid>
              {saved && (
                <div className={cn("form-indicator").toClassName()}>
                  <span className={cn("form-indicator").elem("item").mod({ type: "success" }).toClassName()}>
                    已保存
                  </span>
                </div>
              )}
              <Button className="w-[120px] labeling-save-config-button" onClick={onSave} waiting={waiting} aria-label="保存配置">
                {waiting ? "正在保存…" : "保存配置"}
              </Button>
              {isFF(FF_UNSAVED_CHANGES) && <UnsavedChanges hasChanges={hasChanges} onSave={onSave} />}
            </Form.Actions>
          )}
        </div>
        <div className="relative">
          <EditorResizer
            containerRef={containerRef}
            editorWidthPixels={editorWidthPixels}
            onResize={setEditorWidthPixels}
            constraints={constraints}
            disabled={constraints.minEditorWidth >= constraints.maxEditorWidth}
          />
          <AdaptivePreview
            config={configToDisplay}
            data={data}
            project={project}
            loading={loading}
            error={previewError}
            hasPendingUpdate={manualUpdateMode}
            onUpdatePreview={handleManualUpdate}
            isUpdating={loading}
          />
        </div>
      </div>
    </div>
  );
};

export const ConfigPage = ({
  config: initialConfig = "",
  onRecipe,
  columns: externalColumns,
  project,
  onUpdate,
  onSaveClick,
  onValidate,
  disableSaveButton,
  show = true,
  hasChanges,
}) => {
  const [config, _setConfig] = React.useState("");
  const [mode, setMode] = React.useState("list"); // view | list
  const [selectedGroup, _setSelectedGroup] = React.useState(null);
  const [selectedRecipe, setSelectedRecipe] = React.useState(null);
  const [template, setCurrentTemplate] = React.useState(null);
  const api = useAPI();

  React.useEffect(() => {
    window.__labelingTemplateBrowserMode = mode === "list";
    window.dispatchEvent(
      new CustomEvent("labeling-template-browser-mode", {
        detail: { active: mode === "list" },
      }),
    );
  }, [mode]);

  React.useEffect(() => {
    const handleBack = () => setMode("view");
    window.addEventListener("labeling-template-browser-back", handleBack);
    return () =>
      window.removeEventListener("labeling-template-browser-back", handleBack);
  }, []);

  const setSelectedGroup = React.useCallback(
    (group) => {
      _setSelectedGroup(group);
      recordLocalConfigAction(`labeling_setup.list.${snakeCase(group)}`);
    },
    [_setSelectedGroup],
  );

  const setConfig = React.useCallback(
    (newConfig) => {
      _setConfig(newConfig);
      onUpdate(newConfig);
    },
    [_setConfig, onUpdate],
  );

  // setTemplate - handles both config state and Template object creation
  const setTemplate = React.useCallback(
    (newConfig) => {
      setConfig(newConfig);
      try {
        const tpl = new Template({ config: newConfig });
        tpl.onConfigUpdate = setConfig;
        setCurrentTemplate(tpl);
      } catch (e) {
        console.error("Template parsing error:", e);
      }
    },
    [setConfig, setCurrentTemplate],
  );

  const [columns, setColumns] = React.useState();

  React.useEffect(() => {
    if (externalColumns?.length) setColumns(externalColumns);
  }, [externalColumns]);

  const [warning, _setWarning] = React.useState();

  React.useEffect(() => {
    const fetchData = async () => {
      if (!externalColumns && project?.id && !columns) {
        const res = await api.callApi("dataSummary", {
          params: { pk: project.id },
          // 404 is ok, and errors here don't matter
          errorFilter: () => true,
        });

        if (res?.common_data_columns) {
          setColumns(res.common_data_columns);
        }
      }
    };
    fetchData();
  }, [project?.id, externalColumns]);

  const onSelectRecipe = React.useCallback((recipe) => {
    if (!recipe) {
      setSelectedRecipe(null);
      setMode("list");
      recordLocalConfigAction("labeling_setup.view.empty");
    } else {
      if(onRecipe?.(recipe)===false)return;
      setTemplate(recipe.config);
      setSelectedRecipe(recipe);
      setMode("view");
      recordLocalConfigAction(`labeling_setup.view.${snakeCase(recipe.group)}.${snakeCase(recipe.title)}`);
    }
  });

  const onCustomTemplate = React.useCallback(() => {
    if(onRecipe?.({id:"custom",version:"1.23.2",title:"自定义配置"})===false)return;
    setTemplate(EMPTY_CONFIG);
    setMode("view");
    recordLocalConfigAction("labeling_setup.view.custom");
  });

  const onBrowse = React.useCallback(() => {
    setMode("list");
    recordLocalConfigAction("labeling_setup.list.browse");
  }, []);

  React.useEffect(() => {
    if (initialConfig) {
      setTemplate(initialConfig);
      setMode("view");
    }
  }, []);

  if (!show) return null;

  return (
    <div className={wizardClass} data-mode="list" id="config-wizard">
      {mode === "list" && (
        <TemplatesList
          case="list"
          selectedGroup={selectedGroup}
          selectedRecipe={selectedRecipe}
          onBack={() => setMode("view")}
          onSelectGroup={setSelectedGroup}
          onSelectRecipe={onSelectRecipe}
          onCustomTemplate={onCustomTemplate}
        />
      )}
      {mode === "view" && (
        <Configurator
          case="view"
          columns={columns}
          config={config}
          project={project}
          selectedRecipe={selectedRecipe}
          template={template}
          setTemplate={setTemplate}
          onBrowse={onBrowse}
          onValidate={onValidate}
          disableSaveButton={disableSaveButton}
          onSaveClick={onSaveClick}
          warning={warning}
          hasChanges={hasChanges}
        />
      )}
    </div>
  );
};
