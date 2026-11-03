import { useEffect, useRef, useState } from 'react';
import { Button, Card, Progress, Space, Table, Modal, Input, message } from 'antd';
import { UploadOutlined, FileZipOutlined } from '@ant-design/icons';
import styles from './index.less';
import { Link } from '@umijs/max';
import { serverJson, type ServerProject } from '../manual-label/serverApi';

export default function ServerProjects() {
  const [projects, setProjects] = useState<ServerProject[]>([]);
  const [progress, setProgress] = useState(0);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [file, setFile] = useState<File>();
  const input = useRef<HTMLInputElement>(null);
  const archiveInput = useRef<HTMLInputElement>(null);
  const resume = useRef<ServerProject | undefined>(undefined);
  const paused = useRef(false);
  async function refresh(): Promise<void> {
    try { setProjects(await serverJson<ServerProject[]>('/projects')); setError(''); }
    catch (e) { setError((e as Error).message); }
  }
  useEffect(() => { void refresh(); const timer = setInterval(() => void refresh(), 4000); return () => { clearInterval(timer); paused.current = true; }; }, []);
  useEffect(() => {
    const handler = (e: BeforeUnloadEvent): void => { if (uploading) { e.preventDefault(); e.returnValue = ''; } };
    window.addEventListener('beforeunload', handler); return () => window.removeEventListener('beforeunload', handler);
  }, [uploading]);
  async function upload(file?: File): Promise<void> {
    if (!file) return;
    let project = resume.current;
    if (project && (file.name !== project.filename || file.size !== project.size)) { message.error('请选择上次上传的同一个文件（名称和大小必须一致）'); return; }
    paused.current = false; setUploading(true); setProgress(0);
    try {
      if (!project) {
        const created = await serverJson<{id: string}>('/projects', { method: 'POST', body: JSON.stringify({ name: name.trim(), filename: file.name, size: file.size }) });
        project = { id: created.id, name: name.trim(), filename: file.name, size: file.size, offset: 0 } as ServerProject;
      }
      setCreating(false); setFile(undefined);
      if (project.offset > 0) await serverJson(`/projects/${project.id}/check`, { method: 'POST', body: file.slice(0, Math.min(project.offset, 8 * 1024 * 1024)) });
      let offset = project.offset;
      while (offset < file.size && !paused.current) {
        let success = false;
        for (let attempt = 0; attempt < 3 && !success; attempt++) {
          try {
            const result = await serverJson<{offset: number}>(`/projects/${project.id}/chunk`, { method: 'PUT', headers: { 'X-Upload-Offset': String(offset), 'Content-Type': 'application/octet-stream' }, body: file.slice(offset, offset + 8 * 1024 * 1024) });
            offset = result.offset; success = true;
          } catch (e) {
            if (attempt === 2) throw e;
            await new Promise(resolve => setTimeout(resolve, 1000));
            const latest = (await serverJson<ServerProject[]>('/projects')).find(p => p.id === project!.id);
            if (latest) offset = latest.offset;
            if (offset === file.size) success = true;
          }
        }
        setProgress(Math.round(offset / file.size * 100));
      }
      if (paused.current) message.info('已暂停。可在列表中选择继续上传');
      else {
        await serverJson(`/projects/${project.id}/complete`, { method: 'POST' });
        message.success('上传完成，正在整理项目数据，完成后即可进入标注');
      }
    } catch (e) { message.error((e as Error).message); }
    finally { setUploading(false); resume.current = undefined; if (input.current) input.current.value = ''; void refresh(); }
  }
  async function retry(project: ServerProject): Promise<void> {
    try { await serverJson(`/projects/${project.id}/complete`, { method: 'POST' }); void refresh(); }
    catch (e) { message.error((e as Error).message); }
  }
  const status: Record<string, string> = { uploading: '待上传 / 可继续', processing: '后台解包中', ready: '可以标注', error: '处理失败' };
  return <main style={{ padding: 28, background: '#edf3fa', minHeight: '100vh' }}>
    <Link to="/platforms">← 返回平台选择</Link>
    <div style={{ display: 'flex', alignItems: 'center', gap: 16, marginTop: 24 }}><img src="/seu-emblem-navy.png" alt="东南大学校徽" width={72} height={72} /><div style={{ color: '#112d52' }}><strong style={{ fontSize: 24 }}>东南大学</strong><div>数据标注平台</div></div></div>
    <h1 style={{ color: '#112d52', marginTop: 20 }}>项目管理</h1>
    <Card style={{ marginBottom: 20 }}>
      <Space wrap><Button type="primary" disabled={uploading} onClick={() => { resume.current = undefined; setName(''); setFile(undefined); setCreating(true); }}>新建项目</Button><Button onClick={() => void refresh()}>刷新列表</Button>{uploading && <Button onClick={() => { paused.current = true; }}>暂停上传</Button>}</Space>
      <input hidden ref={input} type="file" accept=".zip,.tar,.gz,.tgz,.jpg,.jpeg,.png,.webp,.csv,.dat" onChange={e => void upload(e.target.files?.[0])} />
      {uploading && <Progress percent={progress} />}
      {error && <p role="alert" style={{ color: '#b42318' }}>无法读取项目列表：{error}</p>}
    </Card>
    <Table locale={{ emptyText: '还没有项目，点击“新建项目”上传第一份数据集' }} rowKey="id" dataSource={projects} pagination={{ pageSize: 10 }} columns={[
      { title: '项目名称', dataIndex: 'name' },
      { title: '上传的压缩包', dataIndex: 'filename' },
      { title: '文件大小', render: (_, p) => `${(p.size / 1024 ** 2).toFixed(1)} MB` },
      { title: '状态', render: (_, p) => <>{status[p.status] || p.status}{p.error && <p style={{ color: '#b42318' }}>{p.error}</p>}</> },
      { title: '数据文件', render: (_, p) => `${p.count} 个${p.skipped ? ` · 已跳过 ${p.skipped} 个` : ''}` },
      { title: '操作', render: (_, p) => p.status === 'ready' ? <Link to={`/manual-label-server/${p.id}`}>进入标注</Link> : p.status === 'uploading' ? <Button disabled={uploading} onClick={() => { resume.current = p; input.current?.click(); }}>继续上传</Button> : p.status === 'error' ? <Button onClick={() => void retry(p)}>重新处理</Button> : '处理中，可稍后刷新' },
    ]} />
    <Modal className={styles.projectModal} width={560} title="新建标注项目" open={creating} onCancel={() => { if (!uploading) setCreating(false); }} onOk={() => void upload(file)} okText="创建并上传" cancelText="取消" confirmLoading={uploading} okButtonProps={{ disabled: !name.trim() || !file, className: styles.primaryButton }} cancelButtonProps={{ disabled: uploading, className: styles.cancelButton }} closable={!uploading} maskClosable={!uploading} destroyOnClose>
      <div className={styles.field}>
        <label htmlFor="project-name">项目名称</label>
        <Input id="project-name" autoFocus maxLength={240} value={name} disabled={uploading} placeholder="请输入项目名称" onChange={e => setName(e.target.value)} />
      </div>
      <div className={styles.field}>
        <span className={styles.fieldLabel}>数据集</span>
        <div className={styles.uploadPanel}>
          <input hidden ref={archiveInput} id="project-archive" type="file" accept=".zip,.tar,.tar.gz,.tgz" disabled={uploading} onChange={e => { const selected = e.target.files?.[0]; if (!selected) return; setFile(selected); if (!name.trim()) setName(selected.name.replace(/\.(tar\.gz|zip|tar|tgz)$/i, '')); }} />
          {file && <div className={styles.fileSummary} role="status"><FileZipOutlined /><div><strong>{file.name}</strong><small>{(file.size / 1024 ** 2).toFixed(1)} MB</small></div></div>}
          <Button className={styles.uploadButton} icon={<UploadOutlined />} disabled={uploading} onClick={() => archiveInput.current?.click()}>{file ? '重新选择' : '选择压缩包'}</Button>
          <span className={styles.formatHint}>ZIP / TAR / TAR.GZ / TGZ · 最大 10 GB</span>
        </div>
      </div>
    </Modal>
  </main>;
}
