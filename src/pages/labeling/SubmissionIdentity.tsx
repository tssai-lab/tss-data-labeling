import { Typography } from 'antd';
import { formatDisplayDateTime } from '@/utils/formatDateTime';

type Identity = {
  submittedBy?: string;
  submittedByName?: string;
  submittedAt?: string;
};
export default function SubmissionIdentity({
  submission,
}: {
  submission: Identity;
}) {
  return (
    <Typography.Paragraph>
      标注人：
      <span title={submission.submittedBy}>
        {submission.submittedByName || '未知标注人'}
      </span>
      {'　'}提交时间：{formatDisplayDateTime(submission.submittedAt)}
    </Typography.Paragraph>
  );
}
