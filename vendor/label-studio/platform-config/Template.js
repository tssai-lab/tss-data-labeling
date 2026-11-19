import { CONTROLS, OBJECTS } from "./tags";
import { Palette } from "./palette";

export const EMPTY_CONFIG = "<View></View>";
export const DEFAULT_COLUMN = "$undefined$";
export const isEmptyConfig = (config) => ["", EMPTY_CONFIG].includes(config.replace(/\s+/g, ""));

export class Template {
  objects = [];
  controls = [];
  details = false;
  palette = Palette();

  constructor(tpl) {
    this.tpl = tpl;
    this.config = tpl.config;

    const parser = new DOMParser();
    this.$root = parser.parseFromString(this.config, "application/xml");
    if (this.$root.querySelector("parsererror")) throw new Error("XML 配置格式错误");

    this.serializer = new XMLSerializer();

    this.initRoot();
  }

  flatten(el) {
    const tags = [];
    for (const tag of el.children) {
      tags.push(tag);
      if (tag.children.length) tags.push(...this.flatten(tag));
    }
    return tags;
  }

  onConfigUpdate() {
    // should be overwritten
  }

  render() {
    const config = this.serializer.serializeToString(this.$root);
    this.onConfigUpdate(config);
  }

  initRoot() {
    const tags = this.flatten(this.$root);
    this.objects = tags.filter(
      ($tag) => $tag.tagName in OBJECTS && ($tag.getAttribute("value") || $tag.getAttribute("valueList")),
    );
    const names = this.objects.map(($tag) => $tag.getAttribute("name"));
    this.controls = tags.filter(($tag) => names.includes($tag.getAttribute("toName")));

    for (const $object of this.objects) {
      const object = OBJECTS[$object.tagName];
      $object.$controls = this.controls.filter(($tag) => $tag.getAttribute("toName") === $object.getAttribute("name"));
      $object.$controls.forEach(($control) => ($control.$object = $object));

      for (const item in object.settings) {
        object.settings[item].object = $object;
      }

      let settings = { ...object.settings };
      $object.$controls.forEach(($control) => {
        const control = CONTROLS[$control.tagName];

        if (control) {
          for (const item in control.settings) {
            control.settings[item].control = $control;
            control.settings[item].object = $object;
          }

          settings = { ...settings, ...control.settings };
        }
      });
      this.settings = settings;
    }
  }

  // fix `value` of object tags according to current columns from data
  fixColumns(columns) {
    if (columns.length === 1 && columns[0] === DEFAULT_COLUMN) return;
    const existing = this.objects.map((obj) => obj.getAttribute("value").replace(/^\$/, ""));
    const free = columns.filter((c) => !existing.includes(c));
    for (const obj of this.objects) {
      if (!columns.includes(obj.getAttribute("value").replace(/^\$/, ""))) {
        obj.setAttribute("value", `$${free.shift() ?? columns[0]}`);
      }
    }

    this.render();
  }

  addLabels(control, labels) {
    if (!labels) return;
    if (!Array.isArray(labels)) {
      labels = labels
        .split("\n")
        .map((s) => s.trim())
        .filter(Boolean);
    }
    if (!labels.length) return;

    const existing = [...control.children].map((ch) => ch.getAttribute("value"));
    const isChoices = control.tagName === "Choices";

    labels.forEach((label) => {
      if (existing.includes(label)) return;
      existing.push(label);
      const $label = this.$root.createElement(isChoices ? "Choice" : "Label");
      $label.setAttribute("value", label);
      // Display names are editable; serialized identities must stay stable.
      $label.setAttribute("alias", createLabelAlias());
      if (!isChoices) $label.setAttribute("background", this.palette.next().value);
      control.appendChild($label);
    });

    this.render();
  }

  removeLabel($label) {
    $label.parentNode.removeChild($label);
    this.render();
  }

  changeLabel($label, attrs) {
    if (Object.hasOwn(attrs, "value") && !$label.hasAttribute("alias")) {
      $label.setAttribute("alias", $label.getAttribute("value"));
    }
    for (const attr of Object.keys(attrs)) {
      $label.setAttribute(attr, attrs[attr]);
    }
    this.render();
  }
}
const createLabelAlias = () => {
  const cryptoApi = globalThis.crypto;
  if (typeof cryptoApi?.randomUUID === 'function') {
    return `label_${cryptoApi.randomUUID().replace(/-/g, '')}`;
  }

  if (typeof cryptoApi?.getRandomValues === 'function') {
    const bytes = new Uint8Array(16);
    cryptoApi.getRandomValues(bytes);
    const suffix = Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('');
    return `label_${suffix}`;
  }

  return `label_${Date.now().toString(16)}${Math.random().toString(16).slice(2)}`;
};

