import { buildInferenceInputPresentation } from './inferenceInputPresentation.mjs';

export { buildInferenceInputPresentation };

export type InferenceInputPresentation =
  | {
      kind: 'dataset';
      identifier: string;
      displayName: string;
    }
  | {
      kind: 'object';
      identifier: string;
      displayName: string;
      previewKind: 'image' | 'text' | 'unsupported';
    };
