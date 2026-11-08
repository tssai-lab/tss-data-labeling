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
import { useEffect, useState } from 'react';
import type { Submission } from '@/services/labelingTasks';
import { tasks } from '@/services/labelingTasks';
import styles from './index.less';
import Navigation from './Navigation';

export default function SubmissionView() {
  const { submissionId = '' } = useParams<{ submissionId: string }>();
  const [value, setValue] = useState<Submission>();
  const [error, setError] = useState('');
  const [retry, setRetry] = useState(0);
  useEffect(() => {
    const abort = new AbortController();
    setValue(undefined);
    setError('');
    tasks
      .submission(submissionId, abort.signal)
      .then((result) => {
        if (!abort.signal.aborted) setValue(result);
      })
      .catch((e: unknown) => {
        if (!abort.signal.aborted)
          setError(e instanceof Error ? e.message : '提交读取失败');
      });
    return () => abort.abort();
  }, [submissionId, retry]);
  return (
    <main className={styles.page}>
      <section className={styles.content}>
        <Navigation />
        <h1>已提交分类结果</h1>
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
              message="这是整包提交时冻结的结果，只读保存"
              description="标签以该提交绑定规范的稳定 ID 显示，不使用之后修改的分类名称替换历史答案。"
              style={{ marginBottom: 24 }}
            />
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
                { title: '图片编号', dataIndex: 'sampleId' },
                {
                  title: '分类标签 ID',
                  render: (_, item) => item.result.labelId || '—',
                },
                { title: '已提交草稿版本', dataIndex: 'revision' },
              ]}
            />
          </Card>
        )}
      </section>
    </main>
  );
}
