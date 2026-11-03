import { CodeOutlined, EditOutlined } from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { history, useParams, useSearchParams } from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Col,
  Descriptions,
  Empty,
  Form,
  Input,
  Modal,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Tree,
  Typography,
} from 'antd';
import type { Key } from 'react';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import CodePreview from '@/components/CodePreview';
import ZipReadmePanel from '@/components/ZipReadmePanel';
import {
  MODEL_BACKEND_TYPE_OPTIONS,
  MODEL_TYPE_COLORS,
  MODEL_TYPE_VALUE_ENUM,
} from '@/constants/model';
import { resolveModelVersionId } from '@/services/model';
import {
  deleteModelAsset,
  deleteModelVersion,
  downloadModelVersion,
  fetchModelAssetDetail,
  fetchModelConsumerManifest,
  fetchModelVersionCodePreview,
  getModelVersion,
  type ModelConsumerManifest,
  previewModelCode,
  switchModelCurrentVersion,
  updateModelAsset,
} from '@/services/platform';
import { getApiErrorMessage } from '@/utils/apiError';
import {
  buildCodeFileTreeData,
  collectCodeFileTreeExpandedKeys,
} from '@/utils/codeFileTree';
import { beginDownloadProgress } from '@/utils/downloadProgressToast';
import { formatDisplayDateTime } from '@/utils/formatDateTime';

const EMPTY_CODE_FILES: API.ModelCodeFile[] = [];

function sameKeyList(left: Key[], right: Key[]): boolean {
  if (left === right) return true;
  if (left.length !== right.length) return false;
  return left.every((key, index) => key === right[index]);
}

const ONE_MIB = 1024 * 1024;

function formatArtifactSize(sizeBytes: number): string {
  if (sizeBytes < ONE_MIB) {
    return `${sizeBytes} 字节`;
  }
  return `${(sizeBytes / ONE_MIB).toFixed(2)} MB`;
}

const ModelDetail: React.FC = () => {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const [assetInfo, setAssetInfo] = useState<API.ModelAssetDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [detailError, setDetailError] = useState('');
  const detailReadSequence = useRef(0);
  const detailReadActive = useRef(true);
  const detailRouteRef = useRef(id);
  detailRouteRef.current = id;
  const detailQueryRef = useRef(searchParams.toString());
  detailQueryRef.current = searchParams.toString();
  const [selectedVersionId, setSelectedVersionId] = useState<string>();
  const [codeLoading, setCodeLoading] = useState(false);
  const [codeError, setCodeError] = useState('');
  const [manifestError, setManifestError] = useState('');
  const [readAttempt, setReadAttempt] = useState(0);
  const previewSequence = useRef(0);
  const [versionCode, setVersionCode] = useState<API.ModelVersionDetail | null>(
    null,
  );
  const [codePreviewVisible, setCodePreviewVisible] = useState(false);
  const [selectedCodePath, setSelectedCodePath] = useState<string>();
  const [previewLoading, setPreviewLoading] = useState(false);
  const [expandedFileKeys, setExpandedFileKeys] = useState<Key[]>([]);
  const [consumerManifest, setConsumerManifest] =
    useState<ModelConsumerManifest | null>(null);
  const [manifestLoading, setManifestLoading] = useState(false);

  const [assetModalOpen, setAssetModalOpen] = useState(false);
  const [assetModalLoading, setAssetModalLoading] = useState(false);
  const [assetForm] = Form.useForm();

  const loadAsset = useCallback(async () => {
    const query = searchParams.toString();
    if (!id || !detailReadActive.current || detailRouteRef.current !== id ||
        detailQueryRef.current !== query) return;
    const request = ++detailReadSequence.current;
    const isCurrent = () => detailReadActive.current &&
      detailRouteRef.current === id && detailQueryRef.current === query &&
      request === detailReadSequence.current;
    setLoading(true);
    try {
      const assetId = id;
      if (/^model-ver-/i.test(id)) {
        const verRes = await getModelVersion(id, { skipErrorHandler: true });
        if (!isCurrent()) return;
        const aid = verRes?.data?.assetId;
        if (aid) {
          history.replace(
            `/model/detail/${encodeURIComponent(aid)}?versionId=${encodeURIComponent(id)}`,
          );
          return;
        }
      }

      const res = await fetchModelAssetDetail(assetId, {
        skipErrorHandler: true,
      });
      if (!isCurrent()) return;
      const detail = res?.data ?? null;
      if (!detail || detail.id !== assetId || !Array.isArray(detail.versions) ||
          ('success' in res && res.success === false) || ('errorCode' in res && res.errorCode)) {
        throw new Error('模型详情回执不完整或资产不匹配，请重试');
      }
      setAssetInfo(detail);
      setDetailError('');

      const queryVersionId = searchParams.get('versionId') ?? undefined;
      const defaultVersionId =
        (queryVersionId && queryVersionId !== assetId
          ? queryVersionId
          : undefined) ??
        detail?.currentVersionId ??
        detail?.defaultVersionId ??
        resolveModelVersionId(detail?.latestVersion, assetId) ??
        detail?.versions
          .map((v) => resolveModelVersionId(v, assetId))
          .find(Boolean);
      setSelectedVersionId(defaultVersionId);
    } catch (error: unknown) {
      if (isCurrent()) setDetailError(getApiErrorMessage(error, '模型详情读取失败，请重试。'));
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [id, searchParams]);

  useEffect(() => {
    // 换资产清理旧表单与版本；服务读取序号另行隔离同资产的重复刷新。
    setAssetInfo(null);
    setSelectedVersionId(undefined);
    setDetailError('');
    setAssetModalOpen(false);
  }, [id]);

  useEffect(() => {
    detailReadActive.current = true;
    void loadAsset();
    return () => {
      detailReadActive.current = false;
      ++detailReadSequence.current;
    };
  }, [loadAsset]);

  useEffect(() => {
    let cancelled = false;
    ++previewSequence.current;
    setCodeError('');
    setVersionCode(null);
    setSelectedCodePath(undefined);
    setExpandedFileKeys([]);
    setPreviewLoading(false);
    setCodeLoading(Boolean(selectedVersionId));
    if (selectedVersionId) {
      fetchModelVersionCodePreview(selectedVersionId, {
        skipErrorHandler: true,
      })
        .then((res) => {
          if (cancelled) return;
          const data = res?.data ?? null;
          setVersionCode(data);
          setSelectedCodePath(data?.codeFilePath || data?.codeFiles?.[0]?.path);
        })
        .catch((error) => {
          if (!cancelled)
            setCodeError(getApiErrorMessage(error, '模型代码读取失败'));
        })
        .finally(() => {
          if (!cancelled) setCodeLoading(false);
        });
    }
    return () => {
      cancelled = true;
      ++previewSequence.current;
    };
  }, [selectedVersionId, readAttempt]);

  const codeFiles = versionCode?.codeFiles ?? EMPTY_CODE_FILES;
  const readmeFilePaths = useMemo(
    () =>
      codeFiles
        .map((f) => f.path || f.fileName)
        .filter((p): p is string => !!p),
    [codeFiles],
  );
  const fileTreeData = useMemo(
    () => buildCodeFileTreeData(codeFiles),
    [codeFiles],
  );
  const defaultExpandedKeys = useMemo(
    () => collectCodeFileTreeExpandedKeys(fileTreeData),
    [fileTreeData],
  );
  const selectedFileKeys = useMemo(
    () => (selectedCodePath ? [selectedCodePath] : []),
    [selectedCodePath],
  );

  useEffect(() => {
    setExpandedFileKeys((prev) =>
      sameKeyList(prev, defaultExpandedKeys) ? prev : defaultExpandedKeys,
    );
  }, [defaultExpandedKeys]);

  useEffect(() => {
    setManifestError('');
    setConsumerManifest(null);
    setManifestLoading(false);
    if (!selectedVersionId) {
      return;
    }
    let cancelled = false;
    setManifestLoading(true);
    fetchModelConsumerManifest(selectedVersionId, { skipErrorHandler: true })
      .then((manifest) => {
        if (!cancelled) setConsumerManifest(manifest);
      })
      .catch((error) => {
        if (!cancelled)
          setManifestError(getApiErrorMessage(error, '模型消费清单读取失败'));
      })
      .finally(() => {
        if (!cancelled) setManifestLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [selectedVersionId, readAttempt]);

  const handleSelectCodeFile = async (path: string) => {
    if (!selectedVersionId || !path) return;
    if (path === selectedCodePath && versionCode?.codeContent) {
      return;
    }
    const request = ++previewSequence.current;
    setCodeError('');
    setVersionCode((prev) =>
      prev ? { ...prev, codeContent: undefined } : prev,
    );
    setSelectedCodePath(path);
    setPreviewLoading(true);
    try {
      const previewRes = await previewModelCode(selectedVersionId, path, {
        skipErrorHandler: true,
      });
      if (request !== previewSequence.current) return;
      const preview = previewRes?.data;
      setVersionCode((prev) =>
        prev
          ? {
              ...prev,
              codeContent: preview?.content,
              codeFileName: preview?.fileName || path.split('/').pop() || path,
              codeFilePath: preview?.path || path,
            }
          : prev,
      );
    } catch (error: unknown) {
      if (request === previewSequence.current)
        setCodeError(getApiErrorMessage(error, '预览该文件失败'));
    } finally {
      if (request === previewSequence.current) setPreviewLoading(false);
    }
  };

  const handleDeleteAsset = async () => {
    if (!id) return;
    try {
      await deleteModelAsset(id);
      message.success('模型资产已删除');
      history.push('/model/list');
    } catch (error: any) {
      message.error(error?.info?.message || error?.message || '删除失败');
    }
  };

  const handleDownload = async (record: API.ModelVersionDetail) => {
    const versionId = resolveModelVersionId(record, assetInfo?.id) ?? record.id;
    if (!versionId) {
      message.warning('当前版本没有可下载文件');
      return;
    }
    const progress = beginDownloadProgress();
    try {
      await downloadModelVersion(versionId, record.fileName, {
        skipErrorHandler: true,
        onProgress: progress.update,
      });
      progress.close();
      message.success('已交给浏览器下载');
    } catch (error: any) {
      progress.close();
      const tip = error?.info?.message || error?.message || '下载失败';
      if (tip === '已取消下载') return;
      message.error(tip);
    }
  };

  const handleSetCurrentVersion = async (record: API.ModelVersionDetail) => {
    if (!id || !assetInfo) return;
    const versionId = resolveModelVersionId(record, assetInfo.id) ?? record.id;
    if (!versionId) return;
    try {
      await switchModelCurrentVersion(id, versionId, {
        skipErrorHandler: true,
      });
      message.success('已设为当前版本');
      await loadAsset();
    } catch (error: any) {
      message.error(
        error?.info?.message || error?.message || '切换当前版本失败',
      );
    }
  };

  const openEditAsset = () => {
    if (!assetInfo) return;
    assetForm.setFieldsValue({
      name: assetInfo.name,
      type: assetInfo.type === 'OTHER' ? undefined : assetInfo.type,
      remark: assetInfo.remark,
    });
    setAssetModalOpen(true);
  };

  const submitEditAsset = async () => {
    if (!id) return;
    try {
      const values = await assetForm.validateFields();
      setAssetModalLoading(true);
      await updateModelAsset(id, values, { skipErrorHandler: true });
      message.success('模型资产已更新');
      setAssetModalOpen(false);
      await loadAsset();
    } catch (error: any) {
      if (error?.errorFields) return;
      message.error(error?.info?.message || error?.message || '更新失败');
    } finally {
      setAssetModalLoading(false);
    }
  };

  const handleDeleteVersion = async (versionId: string) => {
    try {
      await deleteModelVersion(versionId);
      message.success('版本已删除');
      if (selectedVersionId === versionId) {
        setSelectedVersionId(undefined);
      }
      await loadAsset();
    } catch (error: any) {
      message.error(error?.info?.message || error?.message || '删除失败');
    }
  };

  const handleStartTraining = (versionId?: string) => {
    const id = versionId || selectedVersionId;
    if (!id) {
      message.warning('请先在版本列表中选中要用于训练的模型版本');
      return;
    }
    if (!assetInfo?.id) {
      message.warning('模型资产信息未加载完成');
      return;
    }
    const params = new URLSearchParams({
      baseModelVersionId: id,
      from: 'model',
      assetId: assetInfo.id,
    });
    history.push(`/task/create?${params.toString()}`);
  };

  const selectedVersion = assetInfo?.versions.find(
    (v) => v.id === selectedVersionId,
  );

  if (loading || (assetInfo && assetInfo.id !== id)) {
    return (
      <PageContainer
        title="模型详情"
        onBack={() => history.push('/model/list')}
      >
        <div style={{ textAlign: 'center', padding: 80 }}>
          <Spin size="large" />
        </div>
      </PageContainer>
    );
  }

  if (detailError) {
    return <PageContainer title="模型详情" onBack={() => history.push('/model/list')}>
      <Alert type="error" showIcon message="模型详情读取失败" description={detailError}
        action={<Button onClick={() => void loadAsset()}>重试详情</Button>} />
    </PageContainer>;
  }

  if (!assetInfo) {
    return (
      <PageContainer
        title="模型详情"
        onBack={() => history.push('/model/list')}
      >
        <Empty description="未找到模型资产" />
      </PageContainer>
    );
  }

  return (
    <PageContainer
      title="模型详情"
      subTitle="模型资产与已上传的版本文件"
      onBack={() => history.push('/model/list')}
      extra={
        <Space>
          <Button
            type="primary"
            disabled={!selectedVersionId}
            onClick={() => handleStartTraining()}
          >
            用此版本发起训练
          </Button>
          <Button
            onClick={() =>
              history.push(
                `/model/upload?assetId=${encodeURIComponent(assetInfo.id)}&modelName=${encodeURIComponent(assetInfo.name)}&type=${encodeURIComponent(assetInfo.type)}`,
              )
            }
          >
            上传新版本
          </Button>
          <Button icon={<EditOutlined />} onClick={openEditAsset}>
            编辑资产
          </Button>
          <Popconfirm
            title="确认删除该模型资产？将删除其下全部版本。"
            onConfirm={handleDeleteAsset}
          >
            <Button danger>删除资产</Button>
          </Popconfirm>
          <Button onClick={() => history.push('/model/list')}>返回列表</Button>
        </Space>
      }
    >
      <Alert
        type="info"
        showIcon
        style={{ marginBottom: 16 }}
        message="模型版本与训练"
        description={
          <>
            <strong>模型版本</strong>
            ：上传
            zip（预训练权重等）时填写版本号自动创建，用于资产归档与文件预览。
            <br />
            <strong>发起训练</strong>
            ：在「版本」Tab
            选中某一版本后，点击「用此版本发起训练」，会带上该版本进入创建页（作为基础模型权重）。训练代码与数据集请在创建页另行选择或上传。
            <br />
            <strong>继续调参训练</strong>
            ：若要在某次训练结果上继续，请到「训练任务详情」使用
            <Typography.Link onClick={() => history.push('/task/list')}>
              基于此版本继续训练
            </Typography.Link>
            。
            <br />
            如需新增模型文件版本，请使用右上角「上传新版本」并填写新的版本号。
          </>
        }
      />

      {(codeError || manifestError) && (
        <Alert
          type="error"
          showIcon
          message="部分模型信息读取失败"
          description={[codeError, manifestError].filter(Boolean).join('；')}
          action={
            <Button
              loading={codeLoading || manifestLoading}
              onClick={() => setReadAttempt((n) => n + 1)}
            >
              重试读取
            </Button>
          }
        />
      )}
      <Card title="资产信息" style={{ marginBottom: 16 }}>
        <Descriptions column={2}>
          <Descriptions.Item label="模型名称">
            <strong>{assetInfo.name}</strong>
          </Descriptions.Item>
          <Descriptions.Item label="类型">
            <Tag color={MODEL_TYPE_COLORS[assetInfo.type] ?? 'default'}>
              {MODEL_TYPE_VALUE_ENUM[assetInfo.type]?.text ?? assetInfo.type}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="资产 ID">
            <Typography.Text copyable code style={{ fontSize: 12 }}>
              {assetInfo.id}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="当前版本">
            {assetInfo.currentVersionId ||
              assetInfo.defaultVersionId ||
              selectedVersion?.version ||
              '-'}
          </Descriptions.Item>
          <Descriptions.Item label="版本数量">
            {assetInfo.versions.length}
          </Descriptions.Item>
          <Descriptions.Item label="最近上传">
            {formatDisplayDateTime(assetInfo.uploadTime)}
          </Descriptions.Item>
          <Descriptions.Item label="更新时间">
            {formatDisplayDateTime(assetInfo.updatedAt)}
          </Descriptions.Item>
          <Descriptions.Item label="备注" span={2}>
            {assetInfo.remark || '-'}
          </Descriptions.Item>
        </Descriptions>
      </Card>

      <Card styles={{ body: { paddingTop: 8 } }}>
        <Tabs
          defaultActiveKey="readme"
          items={[
            {
              key: 'readme',
              label: 'README',
              children: (
                <ZipReadmePanel
                  source="model"
                  versionId={selectedVersionId}
                  filePaths={readmeFilePaths}
                />
              ),
            },
            {
              key: 'files',
              label: selectedVersion
                ? `文件预览 · ${selectedVersion.version}`
                : '文件预览',
              children: (
                <>
                  {!selectedVersionId && (
                    <Empty
                      description="请从「版本」Tab 选择一个版本"
                      style={{ padding: 48 }}
                    />
                  )}
                  {selectedVersionId && codeLoading && (
                    <div style={{ textAlign: 'center', padding: 48 }}>
                      <Spin tip="加载代码…" />
                    </div>
                  )}
                  {selectedVersionId &&
                    !codeLoading &&
                    fileTreeData.length > 0 && (
                      <Row gutter={16}>
                        <Col xs={24} md={8}>
                          <Typography.Text
                            type="secondary"
                            style={{ display: 'block', marginBottom: 8 }}
                          >
                            包内可预览文件（{codeFiles.length}）
                          </Typography.Text>
                          <div
                            style={{
                              maxHeight: 420,
                              overflow: 'auto',
                              border: '1px solid #f0f0f0',
                              borderRadius: 6,
                              padding: 8,
                            }}
                          >
                            <Tree.DirectoryTree
                              treeData={fileTreeData}
                              selectedKeys={selectedFileKeys}
                              expandedKeys={expandedFileKeys}
                              onExpand={(keys) => {
                                setExpandedFileKeys((prev) =>
                                  sameKeyList(prev, keys) ? prev : keys,
                                );
                              }}
                              expandAction="click"
                              onSelect={(keys, info) => {
                                if (!info.node.isLeaf) return;
                                const path = String(keys[0] || '');
                                if (path) void handleSelectCodeFile(path);
                              }}
                            />
                          </div>
                        </Col>
                        <Col xs={24} md={16}>
                          {previewLoading ? (
                            <div style={{ textAlign: 'center', padding: 48 }}>
                              <Spin tip="加载文件内容…" />
                            </div>
                          ) : versionCode?.codeContent ? (
                            <>
                              <Descriptions
                                size="small"
                                column={1}
                                style={{ marginBottom: 12 }}
                              >
                                <Descriptions.Item label="预览文件">
                                  {versionCode.codeFileName ||
                                    versionCode.codeFilePath ||
                                    '-'}
                                </Descriptions.Item>
                                <Descriptions.Item label="存储路径">
                                  <span
                                    style={{
                                      fontFamily: 'monospace',
                                      fontSize: 12,
                                    }}
                                  >
                                    {versionCode.storagePath || '-'}
                                  </span>
                                </Descriptions.Item>
                              </Descriptions>
                              <pre
                                style={{
                                  background: '#f5f5f5',
                                  border: '1px solid #d9d9d9',
                                  borderRadius: 6,
                                  padding: 16,
                                  maxHeight: 400,
                                  overflow: 'auto',
                                  margin: 0,
                                  fontFamily: 'Courier New, monospace',
                                  fontSize: 13,
                                  lineHeight: 1.6,
                                }}
                              >
                                {versionCode.codeContent}
                              </pre>
                              <Space style={{ marginTop: 12 }}>
                                <Button
                                  type="primary"
                                  size="small"
                                  icon={<CodeOutlined />}
                                  onClick={() => setCodePreviewVisible(true)}
                                >
                                  弹窗查看
                                </Button>
                              </Space>
                            </>
                          ) : (
                            <Empty
                              image={Empty.PRESENTED_IMAGE_SIMPLE}
                              description="请从左侧文件树选择文件预览"
                            />
                          )}
                        </Col>
                      </Row>
                    )}
                  {selectedVersionId &&
                    !codeLoading &&
                    fileTreeData.length === 0 && (
                      <Empty
                        image={Empty.PRESENTED_IMAGE_SIMPLE}
                        description={
                          selectedVersion
                            ? '当前版本包中没有可预览的代码/文本文件（权重等二进制不会出现在此列表）'
                            : '该版本尚未绑定模型文件，请先上传'
                        }
                      />
                    )}
                </>
              ),
            },
            {
              key: 'versions',
              label: '版本',
              children: (
                <>
                  <Table
                    dataSource={assetInfo.versions}
                    rowKey="id"
                    pagination={false}
                    scroll={{ x: 960 }}
                    locale={{
                      emptyText:
                        '暂无版本，请通过「上传新版本」或模型上传页首次上传',
                    }}
                    onRow={(record) => ({
                      onClick: () => setSelectedVersionId(record.id),
                      style: {
                        cursor: 'pointer',
                        background:
                          record.id === selectedVersionId
                            ? '#e6f4ff'
                            : undefined,
                      },
                    })}
                    columns={[
                      {
                        title: '版本号',
                        dataIndex: 'version',
                        key: 'version',
                        width: 120,
                        render: (
                          value: string,
                          record: API.ModelVersionDetail,
                        ) => (
                          <Space size={4}>
                            <span>{value}</span>
                            {(record.isCurrent ||
                              record.id === assetInfo.currentVersionId) && (
                              <Tag color="blue">当前</Tag>
                            )}
                          </Space>
                        ),
                      },
                      {
                        title: '版本 ID',
                        dataIndex: 'id',
                        key: 'id',
                        width: 160,
                        ellipsis: true,
                        render: (v: string) => (
                          <Tooltip title={v}>
                            <Typography.Text code style={{ fontSize: 11 }}>
                              {v}
                            </Typography.Text>
                          </Tooltip>
                        ),
                      },
                      {
                        title: '文件名',
                        dataIndex: 'fileName',
                        key: 'fileName',
                        width: 140,
                        ellipsis: true,
                      },
                      {
                        title: '大小',
                        dataIndex: 'size',
                        key: 'size',
                        width: 100,
                      },
                      {
                        title: '上传时间',
                        dataIndex: 'createdAt',
                        key: 'createdAt',
                        width: 180,
                        render: (value?: string) =>
                          formatDisplayDateTime(value),
                      },
                      {
                        title: '操作',
                        key: 'action',
                        width: 360,
                        fixed: 'right',
                        align: 'left',
                        render: (_, record: API.ModelVersionDetail) => {
                          const isCurrent =
                            record.isCurrent ||
                            record.id === assetInfo.currentVersionId;
                          return (
                            <Space
                              size={0}
                              split={
                                <span style={{ color: '#f0f0f0' }}>|</span>
                              }
                              onClick={(e) => e.stopPropagation()}
                            >
                              <Button
                                type="link"
                                style={{ paddingLeft: 0 }}
                                onClick={() => setSelectedVersionId(record.id)}
                              >
                                选中
                              </Button>
                              <Button
                                type="link"
                                onClick={() => handleStartTraining(record.id)}
                              >
                                发起训练
                              </Button>
                              <Button
                                type="link"
                                onClick={() => handleDownload(record)}
                              >
                                下载
                              </Button>
                              {!isCurrent && (
                                <Popconfirm
                                  title="将此版本设为列表和训练的默认当前版本？"
                                  onConfirm={() =>
                                    handleSetCurrentVersion(record)
                                  }
                                >
                                  <Button type="link">设为当前</Button>
                                </Popconfirm>
                              )}
                              <Popconfirm
                                title="确认删除该版本？"
                                onConfirm={() => handleDeleteVersion(record.id)}
                              >
                                <Button type="link" danger>
                                  删除
                                </Button>
                              </Popconfirm>
                            </Space>
                          );
                        },
                      },
                    ]}
                  />
                  <Typography.Text
                    type="secondary"
                    style={{ display: 'block', marginTop: 8 }}
                  >
                    每个版本对应一次上传的 zip
                    文件。可用「设为当前」指定推荐版本；选中后可用「用此版本发起训练」。
                  </Typography.Text>

                  {selectedVersion && (
                    <Card
                      type="inner"
                      title="选中版本元信息"
                      style={{ marginTop: 16 }}
                      extra={
                        manifestLoading ? (
                          <Typography.Text
                            type="secondary"
                            style={{ fontSize: 12 }}
                          >
                            校验消费清单…
                          </Typography.Text>
                        ) : consumerManifest ? (
                          <Tag
                            color={
                              consumerManifest.isCurrent ? 'blue' : 'default'
                            }
                          >
                            {consumerManifest.isCurrent
                              ? '当前版本'
                              : consumerManifest.status}
                          </Tag>
                        ) : null
                      }
                    >
                      <Descriptions column={2} size="small">
                        <Descriptions.Item label="版本号">
                          {selectedVersion.version}
                        </Descriptions.Item>
                        <Descriptions.Item label="状态">
                          {consumerManifest?.status ||
                            selectedVersion.status ||
                            '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="Commit">
                          {consumerManifest?.commitInfo ||
                            selectedVersion.commitInfo ||
                            '-'}
                        </Descriptions.Item>
                        <Descriptions.Item label="SHA-256">
                          <Typography.Text
                            copyable={
                              !!(
                                consumerManifest?.artifactSha256 ||
                                selectedVersion.artifactSha256
                              )
                            }
                            code
                            style={{ fontSize: 11 }}
                          >
                            {consumerManifest?.artifactSha256 ||
                              selectedVersion.artifactSha256 ||
                              '-'}
                          </Typography.Text>
                        </Descriptions.Item>
                        <Descriptions.Item label="制品大小" span={2}>
                          {(() => {
                            const sizeBytes =
                              consumerManifest?.sizeBytes ??
                              selectedVersion.sizeBytes;
                            const fileName =
                              consumerManifest?.fileName ||
                              selectedVersion.fileName;
                            if (sizeBytes == null || Number.isNaN(sizeBytes)) {
                              return fileName || '-';
                            }
                            const sizeLabel = formatArtifactSize(sizeBytes);
                            return fileName
                              ? `${sizeLabel} · ${fileName}`
                              : sizeLabel;
                          })()}
                        </Descriptions.Item>
                        <Descriptions.Item label="超参" span={2}>
                          {(() => {
                            const hp =
                              consumerManifest?.hyperParams ??
                              selectedVersion.hyperParams;
                            return hp && Object.keys(hp).length > 0 ? (
                              <pre
                                style={{
                                  margin: 0,
                                  maxHeight: 200,
                                  overflow: 'auto',
                                  background: '#fafafa',
                                  padding: 8,
                                  borderRadius: 4,
                                }}
                              >
                                {JSON.stringify(hp, null, 2)}
                              </pre>
                            ) : (
                              '无（未填写或为空对象）'
                            );
                          })()}
                        </Descriptions.Item>
                        <Descriptions.Item label="备注" span={2}>
                          {selectedVersion.remark || assetInfo.remark || '-'}
                        </Descriptions.Item>
                      </Descriptions>
                    </Card>
                  )}
                </>
              ),
            },
          ]}
        />
      </Card>

      {codePreviewVisible && versionCode?.codeContent && (
        <CodePreview
          visible={codePreviewVisible}
          codeText={versionCode.codeContent}
          fileName={
            versionCode.codeFileName || versionCode.codeFilePath || 'model-code'
          }
          onClose={() => setCodePreviewVisible(false)}
        />
      )}

      <Modal
        title="编辑模型资产"
        open={assetModalOpen}
        onCancel={() => setAssetModalOpen(false)}
        onOk={submitEditAsset}
        confirmLoading={assetModalLoading}
        destroyOnClose
      >
        <Form form={assetForm} layout="vertical">
          {assetInfo?.type === 'OTHER' && (
            <Alert
              type="warning"
              showIcon
              style={{ marginBottom: 16 }}
              message="历史类型 OTHER"
              description="后端仅支持 CV、NLP、点云、ROBOT；保存前请改选正式类型。"
            />
          )}
          <Form.Item
            name="name"
            label="模型名称"
            rules={[{ required: true, message: '请输入名称' }]}
          >
            <Input />
          </Form.Item>
          <Form.Item
            name="type"
            label="类型"
            rules={[{ required: true, message: '请选择类型' }]}
          >
            <Select options={[...MODEL_BACKEND_TYPE_OPTIONS]} />
          </Form.Item>
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>
    </PageContainer>
  );
};

export default ModelDetail;
