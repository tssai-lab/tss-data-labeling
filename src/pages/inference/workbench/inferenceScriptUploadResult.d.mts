export function requireSavedInferenceScriptVersion(response?: {
  success?: boolean;
  errorMessage?: string;
  data?: { scriptVersionId?: string };
}): string;
