import { history, useParams, useSearchParams } from '@umijs/max';
import React from 'react';
import LeRobotTimelineViewer from '../components/lerobot/LeRobotTimelineViewer';

const LeRobotTimelinePage: React.FC = () => {
  const { versionId } = useParams<{ versionId: string }>();
  const [searchParams] = useSearchParams();
  const assetId = searchParams.get('assetId');
  const close = () => {
    if (assetId) {
      history.push(`/dataset/detail/${encodeURIComponent(assetId)}`);
      return;
    }
    history.back();
  };

  return versionId ? (
    <LeRobotTimelineViewer versionId={versionId} onClose={close} />
  ) : null;
};

export default LeRobotTimelinePage;
