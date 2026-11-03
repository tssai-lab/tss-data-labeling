/** 可选展示名称补全（不是权限判定）。仅整理职责；权限、版本 CAS、重试及兼容规则沿用原实现。 */
import {
  getV2CodeAsset,
  getV2CodeVersion,
  isInternalGeneratedCodeAssetName,
  mapV2CodeVersionToLegacy,
} from '../codeV2';
import { type CodeVersionListItem } from './types';


/** 展示用户填写的训练代码名称，忽略后端自动生成的内部资产名 */
export function getCodeUserDisplayName(
  item?: Pick<CodeVersionListItem, 'codeName' | 'codeAssetName'>,
): string {
  const userName = item?.codeName?.trim();
  if (userName) return userName;
  const legacy = item?.codeAssetName?.trim();
  if (legacy && !isInternalGeneratedCodeAssetName(legacy)) return legacy;
  return '-';
}

export async function fetchCodeAssetMeta(
  assetId: string | undefined,
  options?: { [key: string]: any },
): Promise<{ name?: string; trainingProfile?: string } | undefined> {
  const id = assetId?.trim();
  if (!id) return undefined;
  try {
    const asset = await getV2CodeAsset(id, {
      skipErrorHandler: true,
      ...(options || {}),
    });
    if (!asset) return undefined;
    return {
      name: asset.name?.trim() || undefined,
      trainingProfile: asset.trainingProfile?.trim() || undefined,
    };
  } catch {
    return undefined;
  }
}

export function pickDisplayCodeName(item: CodeVersionListItem): string | undefined {
  const existing = item.codeName?.trim();
  if (existing && !isInternalGeneratedCodeAssetName(existing)) return existing;
  const legacy = item.codeAssetName?.trim();
  if (legacy && !isInternalGeneratedCodeAssetName(legacy)) return legacy;
  return undefined;
}

/**
 * 补全列表/详情展示字段：
 * - codeName：用户填写名称（V2 资产 name）
 * - trainingProfile：版本 DTO 常无此字段，需从资产/manifest 回填
 * - validationStatus / riskLevel：legacy list 不含，需从 V2 版本回填
 */
export async function enrichCodeVersionDisplayFields<T extends CodeVersionListItem>(
  item: T,
  options?: { [key: string]: any } & { enrichRisk?: boolean },
): Promise<T> {
  const enrichRisk = options?.enrichRisk !== false;
  let next: T = { ...item };
  const needName = !pickDisplayCodeName(next);
  const needProfile = !next.trainingProfile?.trim();
  const needRisk =
    enrichRisk && (!next.validationStatus?.trim() || !next.riskLevel?.trim());

  const [assetMeta, v2Version] = await Promise.all([
    needName || needProfile
      ? fetchCodeAssetMeta(next.codeAssetId, options)
      : Promise.resolve(undefined),
    needRisk && next.codeVersionId
      ? getV2CodeVersion(next.codeVersionId, {
          skipErrorHandler: true,
          ...(options || {}),
        }).catch(() => undefined)
      : Promise.resolve(undefined),
  ]);

  if (assetMeta) {
    if (needName) {
      const assetName = assetMeta.name;
      if (assetName && !isInternalGeneratedCodeAssetName(assetName)) {
        next = { ...next, codeName: assetName };
      } else if (assetName) {
        next = { ...next, codeName: assetName };
      }
    }
    if (needProfile && assetMeta.trainingProfile) {
      next = { ...next, trainingProfile: assetMeta.trainingProfile };
    }
  }

  if (v2Version) {
    const mapped = mapV2CodeVersionToLegacy(v2Version);
    next = {
      ...next,
      validationStatus: next.validationStatus || mapped.validationStatus,
      riskLevel: next.riskLevel || mapped.riskLevel,
      riskStatus: next.riskStatus || mapped.riskStatus,
      riskAssessmentId: next.riskAssessmentId || mapped.riskAssessmentId,
      reviewDisposition: next.reviewDisposition || mapped.reviewDisposition,
      riskPolicyVersion: next.riskPolicyVersion || mapped.riskPolicyVersion,
      validationPolicyVersion:
        next.validationPolicyVersion || mapped.validationPolicyVersion,
      artifactSha256: next.artifactSha256 || mapped.artifactSha256,
      trainingProfile: next.trainingProfile || mapped.trainingProfile,
    };
  }

  const displayName = pickDisplayCodeName(next);
  if (displayName && next.codeName !== displayName) {
    next = { ...next, codeName: displayName };
  }
  return next;
}
