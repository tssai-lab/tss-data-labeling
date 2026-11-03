import { Modal } from 'antd';
import type { V2CodeValidationResult } from '@/services/platform';

/** 展示 V2 校验结果弹窗 */
export function showValidationResultModal(data: V2CodeValidationResult) {
  const status = String(
    data.validationStatus || data.status || '',
  ).toUpperCase();
  const passed =
    data.passed === true || data.valid === true || status === 'PASSED';
  Modal.info({
    title: passed ? '校验通过' : '校验未通过',
    width: 520,
    content: (
      <div style={{ lineHeight: 1.8 }}>
        <div>状态：{status || '-'}</div>
        {data.reused ? <div>结果：幂等复用（reused=true）</div> : null}
        {data.message ? <div>{data.message}</div> : null}
        {data.reasonCode ? <div>reasonCode：{data.reasonCode}</div> : null}
        {data.policyVersion ? (
          <div>policyVersion：{data.policyVersion}</div>
        ) : null}
        {data.artifactSha256 ? (
          <div>artifactSha256：{data.artifactSha256}</div>
        ) : null}
        {data.checkedAt ? <div>checkedAt：{data.checkedAt}</div> : null}
      </div>
    ),
  });
}
