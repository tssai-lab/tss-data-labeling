export { createEditorSession } from './editorSession.mjs';
import '../../vendor/label-studio/libs/editor/src/core/feature-flags';
import 'antd/dist/antd.css';
import '../../vendor/label-studio/libs/editor/src/assets/styles/global.scss';
import '../../vendor/label-studio/libs/ui/src/styles.scss';
import '../../vendor/label-studio/libs/ui/src/tailwind.css';

export { acquireEditorLease } from './editorLifecycle.mjs';
export {
  mountNativeTemplateConfigurator,
  validateLabelConfig,
  waitForPreviewDisposal,
} from './nativeConfig';

import { previewData } from './nativeConfig';
export const createLocalEditorTask = (config, task, urls) =>
  previewData(config, task, urls, true);

export { LabelStudio } from '../../vendor/label-studio/libs/editor/src/LabelStudio';

// CSS stays in the same document, inside the editor's ShadowRoot, never an iframe.
export function attachStyles(root) {
  // FontFace declarations in a shadow tree are not registered by Chromium.
  // Publish only upstream font faces in the document; all component CSS stays isolated.
  if (!document.querySelector('style[data-platform-ls-fonts]')) {
    const fonts = document.createElement('style');
    fonts.dataset.platformLsFonts = 'true';
    fonts.textContent = (window.__platformLsStyles || [])
      .flatMap(
        (style) => style.textContent.match(/@font-face\s*\{[^}]*\}/g) || [],
      )
      .join('\n');
    document.head.append(fonts);
  }
  for (const style of window.__platformLsStyles || []) {
    const copy = style.cloneNode(true);
    // Upstream design tokens target :root; inside Shadow DOM their root is :host.
    copy.textContent = copy.textContent
      .replace(/:root\b/g, ':host')
      .replace(/(^|})\s*body\s*\{/g, '$1 :host {');
    root.append(copy);
  }
  const defaults = document.createElement('style');
  defaults.textContent =
    ':host { display:block; font:14px/1.5715 Figtree,sans-serif; --font-sans:Figtree,sans-serif; --font-mono:monaco,monospace; }';
  root.append(defaults);
}
