import { useParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Spin,
  Table,
  Typography,
} from 'antd';
import { useEffect, useRef, useState } from 'react';
import {
  describeLabelingError,
  labelingJson,
  type Page,
  type Project,
  pageQuery,
  type Sample,
  type SpecVersion,
  sampleBlob,
} from '@/services/labeling';
import { resultLabel } from '@/services/labelingStructured';
import type { Submission } from '@/services/labelingTasks';
import { allWorkItems, tasks } from '@/services/labelingTasks';
import StructuredPreview from './StructuredPreview';
import { ReviewConclusion } from './SubmissionReview';
import WorkspaceLayout from './WorkspaceLayout';

export default function SubmissionView() {
  const { submissionId = '' } = useParams<{ submissionId: string }>();
  const [spec, setSpec] = useState<SpecVersion>();
  const [samples, setSamples] = useState<Sample[]>([]);
  const [structuredSample, setStructuredSample] = useState(''),
    [ownAssignment, setOwnAssignment] = useState(false);
  const [mappingNote, setMappingNote] = useState('');
  const imageAbort = useRef<AbortController | undefined>(undefined);
  const [preview, setPreview] = useState('');
  const [imageError, setImageError] = useState('');
  const [value, setValue] = useState<Submission>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setValue(undefined);
    setStructuredSample('');
    setOwnAssignment(false);
    setSpec(undefined);
    setSamples([]);
    setMappingNote('');
    setPreview('');
    setImageError('');
    imageAbort.current?.abort();
    setError('');
    tasks
      .submission(submissionId, abort.signal)
      .then(async (result) => {
        if (abort.signal.aborted) return;
        setValue(result);
        try {
          const project = await labelingJson<Project>(
            `/projects/${encodeURIComponent(result.projectId)}`,
            { signal: abort.signal },
          );
          if (abort.signal.aborted) return;
          setSpec(
            project.specVersions?.find(
              (s) => s.specVersionId === result.specVersionId,
            ),
          );
          const me = await tasks.me(abort.signal);
          const key = `labeling:last-batch:${me.actorId}:${result.projectId}`;
          const parsed = JSON.parse(
            localStorage.getItem(`${key}:known`) || '[]',
          );
          const ids = [
            ...new Set(
              [
                localStorage.getItem(key),
                ...(Array.isArray(parsed) ? parsed : []),
              ].filter(
                (id): id is string => typeof id === 'string' && Boolean(id),
              ),
            ),
          ];
          const found: Sample[] = [];
          for (const id of ids) {
            let cursor: string | null = null;
            const seen = new Set<string>();
            do {
              const page: Page<Sample> = await labelingJson<Page<Sample>>(
                `/imports/${encodeURIComponent(id)}/samples?${pageQuery(cursor)}`,
                { signal: abort.signal },
              );
              if (abort.signal.aborted) return;
              found.push(...page.items);
              cursor = page.nextCursor;
              if (cursor && seen.has(cursor))
                throw new Error('样本分页游标重复');
              if (cursor) seen.add(cursor);
            } while (cursor);
          }
          if (!abort.signal.aborted) {
            setSamples(found);
            setMappingNote(
              '文件名仅匹配本机已知批次的真实样本；未匹配项保留真实 ID。标签名称来自提交冻结规范。',
            );
          }
        } catch (e) {
          try {
            const assignment = await allWorkItems(
              result.assignmentId,
              abort.signal,
            );
            if (!abort.signal.aborted) {
              setSpec({ ...assignment.specification, versionNo: 0 });
              setOwnAssignment(true);
            }
          } catch {
            /* Owner spec read failure remains visible. */
          }
          if (!abort.signal.aborted)
            setMappingNote(
              `来源映射不可用，保留真实样本与标签 ID。${describeLabelingError(e)}`,
            );
        }
      })
      .catch((e: unknown) => {
        if (!abort.signal.aborted) setError(describeLabelingError(e));
      });
    return () => {
      abort.abort();
      imageAbort.current?.abort();
    };
  }, [submissionId, retry]);
  useEffect(
    () => () => {
      if (preview) URL.revokeObjectURL(preview);
    },
    [preview],
  );
  async function showImage(sampleId: string) {
    if (!value) return;
    setImageError('');
    imageAbort.current?.abort();
    const abort = new AbortController();
    imageAbort.current = abort;
    try {
      const blob = await sampleBlob(value.projectId, sampleId, abort.signal);
      if (!abort.signal.aborted) setPreview(URL.createObjectURL(blob));
    } catch (e) {
      if (!abort.signal.aborted) setImageError(describeLabelingError(e));
    }
  }
  return (
    <WorkspaceLayout>
      <h1>已交回的人工包结果</h1>
      {error && (
        <Alert
          type="error"
          showIcon
          message={error}
          action={<Button onClick={() => setRetry(retry + 1)}>重试</Button>}
        />
      )}
      {!error && !value && <Spin />}
      {value && (
        <Card>
          <Alert
            type="success"
            showIcon
            message="本包人工结果已交回并冻结，只读待需求方审核"
            description="人工提交不表示审核通过或整个任务完成。标签使用提交绑定的冻结规范，不使用之后的分类名称覆盖历史。"
            style={{ marginBottom: 24 }}
          />
          {mappingNote && <Alert type="info" message={mappingNote} />}
          {imageError && <Alert type="error" message={imageError} />}
          {preview && (
            <img
              src={preview}
              alt="授权原图预览"
              style={{
                maxWidth: '100%',
                maxHeight: 400,
                objectFit: 'contain',
              }}
            />
          )}
          {structuredSample && spec && (
            <StructuredPreview
              projectId={value.projectId}
              assignmentId={ownAssignment ? value.assignmentId : undefined}
              spec={spec}
              row={{
                sampleId: structuredSample,
                resultId:
                  value.items.find((i) => i.sampleId === structuredSample)
                    ?.resultId || null,
                reviewRevision: 0,
                result:
                  value.items.find((i) => i.sampleId === structuredSample)
                    ?.result || null,
              }}
            />
          )}
          <Descriptions
            column={1}
            items={[
              {
                key: 'id',
                label: '提交编号',
                children: (
                  <Typography.Text copyable>
                    {value.submissionId}
                  </Typography.Text>
                ),
              },
              {
                key: 'actor',
                label: '标注人编号',
                children: value.submittedBy,
              },
              {
                key: 'time',
                label: '提交时间',
                children: new Date(value.submittedAt).toLocaleString('zh-CN'),
              },
              {
                key: 'spec',
                label: '规范版本编号',
                children: value.specVersionId,
              },
            ]}
          />
          <Table
            rowKey="sampleId"
            pagination={{ pageSize: 20 }}
            dataSource={value.items}
            scroll={{ x: 550 }}
            columns={[
              {
                title: '来源路径 / 样本编号',
                render: (_, item) =>
                  samples.find((s) => s.sampleId === item.sampleId)
                    ?.sourcePath || item.sampleId,
              },
              {
                title: '主标签',
                render: (_, item) =>
                  spec?.labels.find((l) => l.id === resultLabel(item.result))
                    ?.name ||
                  resultLabel(item.result) ||
                  '—',
              },
              { title: '已提交草稿版本', dataIndex: 'revision' },
              {
                title: '样本数据',
                render: (_, item) => (
                  <Button
                    onClick={() =>
                      item.resultFormat === 'annotation-record-v1'
                        ? setStructuredSample(item.sampleId)
                        : void showImage(item.sampleId)
                    }
                  >
                    授权读取
                  </Button>
                ),
              },
            ]}
          />
        </Card>
      )}
      {value && <ReviewConclusion submissionId={value.submissionId} />}
    </WorkspaceLayout>
  );
}
