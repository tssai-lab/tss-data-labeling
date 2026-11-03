import {
  BookOutlined,
  CloudUploadOutlined,
  DatabaseOutlined,
  DownloadOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  LinkOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  ThunderboltOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import { PageContainer } from '@ant-design/pro-components';
import { Link } from '@umijs/max';
import {
  Alert,
  Anchor,
  Card,
  Col,
  Collapse,
  Descriptions,
  Divider,
  Row,
  Space,
  Table,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import React from 'react';

const { Title, Paragraph, Text } = Typography;

// ==================== 约束数据 ====================

const modelConstraints = [
  {
    key: 'fileFormat',
    constraint: '支持的文件格式',
    specification: '.zip、.safetensors、.pt、.pth、.ckpt、.onnx',
    remark: '多文件模型（如千问等大模型）请打包为 .zip 上传',
  },
  {
    key: 'maxSize',
    constraint: '单文件大小上限',
    specification: '2 GB',
    remark: '超过限制请拆分模型或联系管理员扩容',
  },
  {
    key: 'versionFormat',
    constraint: '版本号格式',
    specification: 'vN（如 v2）或 vX.Y.Z（如 v1.0.0）',
    remark: '同一资产内版本号必须唯一，新版本必须严格大于已有版本',
  },
  {
    key: 'modelName',
    constraint: '模型名称',
    specification: '必填，自由文本',
    remark: '同一用户下模型名称不可重复',
  },
  {
    key: 'modelType',
    constraint: '模型类型',
    specification: 'CV / NLP / POINT_CLOUD / ROBOT / OTHER（暂未归类）',
    remark: '上传后不可更改',
  },
  {
    key: 'remark',
    constraint: '资产备注',
    specification: '必填，最长 200 字符',
    remark: '描述整份模型资产的用途/来源，不能为空或纯空格',
  },
  {
    key: 'commitInfo',
    constraint: 'Commit 说明',
    specification: '必填，最长 1024 字符',
    remark: '描述本版本具体变更内容（类似 Git commit message）',
  },
  {
    key: 'hyperParams',
    constraint: '超参（可选）',
    specification: 'JSON 对象，如 {"lr":0.001,"epochs":10}',
    remark: '选填，不填则按空对象上传',
  },
  {
    key: 'chunkUpload',
    constraint: '上传方式',
    specification: '分片断点续传，单片 5 MB',
    remark: '支持断点续传，刷新页面或关闭浏览器后可继续',
  },
];

/** 系统为每位用户预置的默认资产说明 */
const defaultAssets = [
  {
    key: 'dataset',
    asset: '数据集',
    name: '示例数据集',
    type: 'CV 图像分类（ImageFolder）',
    usage: '训练输入，含 train / validation / test 分类图片',
  },
  {
    key: 'model',
    asset: '模型',
    name: '示例模型',
    type: 'MobileNetV2 图像分类',
    usage: '训练基础权重',
  },
  {
    key: 'code',
    asset: '训练代码',
    name: '示例代码',
    type: '图像分类训练代码',
    usage: '训练脚本（已审核通过）',
  },
  {
    key: 'script',
    asset: '推理脚本',
    name: '示例推理脚本',
    type: 'Python 推理脚本',
    usage: '用训练产出模型做图像分类推理',
  },
];

const datasetConstraints = [
  {
    key: 'maxSize',
    constraint: '单文件大小上限',
    specification: '50 GB',
    remark: '大文件自动分片上传，支持断点续传',
  },
  {
    key: 'types',
    constraint: '支持的数据集类型',
    specification: 'CV、NLP、POINT_CLOUD、MULTIMODAL、ROBOT、OTHER',
    remark: '不同类型对应不同的文件格式要求',
  },
  {
    key: 'cvFormat',
    constraint: 'CV 数据集',
    specification: '多文件文件夹上传 或 单文件 .zip',
    remark: '可选标注格式：YOLO / COCO / VOC / CSV / MASK / LABELME 等',
  },
  {
    key: 'nlpFormat',
    constraint: 'NLP 数据集',
    specification: '.zip（将多个文件打包）',
    remark: '仅支持单个 zip 文件上传',
  },
  {
    key: 'pointCloudFormat',
    constraint: '点云数据集',
    specification: '.ply / .pcd / .zip',
    remark: '仅支持单个文件；zip 内需至少包含一个 .ply 或 .pcd',
  },
  {
    key: 'multimodalFormat',
    constraint: '多模态数据集',
    specification: '.zip',
    remark: '支持 AUTO_DIRECTORY（自动目录）和 MANIFEST（索引）两种分组方式',
  },
  {
    key: 'robotFormat',
    constraint: '机器人数据集',
    specification: '.xml / .yaml / .yml / .zip',
    remark: '仅支持单个配置文件或配置类 zip',
  },
  {
    key: 'otherFormat',
    constraint: '暂未归类数据集',
    specification:
      '安全白名单内的图片、文本、XML/YAML、PLY/PCD、Parquet、视频等文件或 .zip',
    remark: '仅用于存储和检索，不自动取得训练资格',
  },
  {
    key: 'versionFormat',
    constraint: '版本号格式',
    specification: 'vN 或 vX.Y.Z（同一资产内唯一）',
    remark: '新版本必须严格大于已有版本',
  },
  {
    key: 'versionDesc',
    constraint: '版本描述',
    specification: '必填，10~2000 字符',
    remark: '须说明更新原因与内容，便于长期维护与训练选型',
  },
];

const trainingCodeConstraints = [
  {
    key: 'maxSize',
    constraint: '文件大小上限',
    specification: '50 MB',
    remark: '仅支持 .zip 格式',
  },
  {
    key: 'allowedFiles',
    constraint: '包内允许的文件类型',
    specification:
      '.py / .pyx / .ipynb / .txt /.json /.jsonl /.yaml /.yml /.md',
    remark: '禁止包含脚本执行器与二进制可执行文件',
  },
  {
    key: 'entrypoint',
    constraint: '入口脚本',
    specification: '须包含训练方案指定的入口文件',
    remark: '如所选方案入口为 train.py，则 zip 根目录必须包含 train.py',
  },
  {
    key: 'review',
    constraint: '审核机制',
    specification: '自动审核 / 人工审核（由系统配置控制）',
    remark: '审核通过后才能在训练代码列表中使用',
  },
];

const commonErrors = [
  {
    error: '文件格式不支持',
    cause: '上传的文件扩展名不在允许列表中',
    solution:
      '检查文件后缀，模型上传支持 .zip/.safetensors/.pt/.pth/.ckpt/.onnx；数据集根据类型不同支持不同格式',
  },
  {
    error: '文件大小超出限制',
    cause: '上传文件超过对应模块的大小上限',
    solution:
      '模型 ≤ 2GB，数据集 ≤ 50GB，训练代码 ≤ 50MB。超限请拆分或联系管理员',
  },
  {
    error: '版本号已存在',
    cause: '同一资产下版本号重复',
    solution: '使用更大的版本号（vN 风格则 v(N+1)，vX.Y.Z 风格则递增版本号）',
  },
  {
    error: '版本号格式不正确',
    cause: '版本号不符合 vN 或 vX.Y.Z 规范',
    solution: '版本号必须以 v 开头，后跟纯数字（v2）或三段式数字（v1.0.0）',
  },
  {
    error: '新版本号不大于已有版本',
    cause: '上传的新版本号小于或等于当前最新版本',
    solution: '页面会提示当前最新版本号，请使用更大的版本号',
  },
  {
    error: '上传中断/失败',
    cause: '网络波动、浏览器关闭、服务端异常等',
    solution: '平台支持断点续传。重新选择同一文件后提交，系统自动从断点继续',
  },
  {
    error: 'Commit 说明为空',
    cause: '未填写版本提交说明或仅输入空格',
    solution: 'Commit 说明为必填项，请简要描述本次版本变更内容',
  },
  {
    error: '超参 JSON 格式错误',
    cause: '超参输入不是合法的 JSON 对象',
    solution:
      '确保输入为合法的 JSON 对象格式，如 {"lr":0.001,"epochs":10}；不填则跳过',
  },
  {
    error: '训练代码审核未通过',
    cause: '代码未通过管理员审核',
    solution:
      '联系管理员在「训练调度 → 待审核」页面进行审核；管理员审核通过后即可使用',
  },
  {
    error: '权限不足（403）',
    cause: '当前账号没有对应操作的权限',
    solution: '联系系统管理员申请相应权限。普通用户可查看资源状态和自己的日志',
  },
  {
    error: '登录失效（401）',
    cause: 'Token 过期或未登录',
    solution: '系统会自动跳转到登录页，请重新登录',
  },
  {
    error: '网络错误 / 无法连接服务器',
    cause: '客户端无法连接到后端服务',
    solution: '检查网络连接，确认后端服务是否正常运行，或联系运维人员',
  },
  {
    error: '请求超时',
    cause: '请求在规定时间内未得到响应',
    solution:
      '大文件上传等待时间较长属正常现象；若频繁超时请检查网络状况或联系运维',
  },
  {
    error: '多模态导入失败',
    cause: 'zip 结构不符合 AUTO_DIRECTORY 或 MANIFEST 规范',
    solution:
      'AUTO_DIRECTORY：zip 根目录直接为样本子目录，根级不能有普通文件。MANIFEST：确保 manifest.json 路径正确且格式合法',
  },
];

// ==================== 样式常量 ====================

const sectionStyle: React.CSSProperties = {
  scrollMarginTop: 80,
};

const cardStyle: React.CSSProperties = {
  marginBottom: 24,
};

const tableStyle: React.CSSProperties = {
  marginTop: 12,
};

const downloadableManuals = [
  {
    key: 'training-plan-yaml',
    title: '自定义训练方案 YAML 使用手册',
    description: '说明平台固定字段、校验规则、完整示例和上线前检查项。',
    href: '/manuals/training-plan-yaml-guide.pdf',
  },
  {
    key: 'training-code',
    title: '自定义训练代码编写手册',
    description: '说明代码包结构、平台输入、训练指标协议和输出文件约定。',
    href: '/manuals/training-code-user-guide.pdf',
  },
  {
    key: 'inference-script',
    title: '自定义推理脚本编写手册',
    description: '说明统一结果协议、CV/NLP 示例和原始输入预览约定。',
    href: '/manuals/inference-script-user-guide.pdf',
  },
];

// ==================== 页面组件 ====================

const UserManual: React.FC = () => {
  return (
    <PageContainer
      ghost
      title="用户手册"
      subTitle="AI 训练平台操作指南 · 数据规范 · 常见问题"
      style={{ maxWidth: 'none' }}
      extra={
        <Space>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>
            版本 v2.0 · 最后更新 2026-08
          </Typography.Text>
        </Space>
      }
    >
      <Row gutter={24}>
        {/* ===== 左侧正文 ===== */}
        <Col xs={24} lg={18} xl={19}>
          {/* ========== 1. 平台概述 ========== */}
          <div id="overview" style={sectionStyle}>
            <Title level={2}>
              <InfoCircleOutlined
                style={{ marginRight: 8, color: '#1890ff' }}
              />
              平台概述
            </Title>
            <Card style={cardStyle}>
              <Paragraph>
                本平台是一站式 AI 模型训练管理平台，覆盖从
                <Text strong>数据准备</Text> → <Text strong>代码上传</Text> →{' '}
                <Text strong>模型训练</Text> → <Text strong>模型管理</Text> →{' '}
                <Text strong>模型推理</Text>
                的完整 AI
                开发流水线。平台提供可视化的资产管理、训练任务调度、GPU
                算力监控等功能，帮助团队高效协作完成 AI 项目交付。
              </Paragraph>
              <Descriptions
                bordered
                size="small"
                column={{ xs: 1, sm: 2, md: 3 }}
                style={{ marginTop: 16 }}
              >
                <Descriptions.Item label="模型管理">
                  模型资产的上传、版本管理、代码预览
                </Descriptions.Item>
                <Descriptions.Item label="数据集管理">
                  多类型数据集的上传、版本与预览
                </Descriptions.Item>
                <Descriptions.Item label="训练调度">
                  训练代码管理、任务创建与 GPU 资源监控
                </Descriptions.Item>
                <Descriptions.Item label="模型推理">
                  在线推理工作台，支持模型验证
                </Descriptions.Item>
                <Descriptions.Item label="系统管理">
                  用户管理、权限控制、操作日志
                </Descriptions.Item>
                <Descriptions.Item label="个人中心">
                  个人操作日志与审计追溯
                </Descriptions.Item>
              </Descriptions>
            </Card>
          </div>

          <Divider />

          <div id="document-downloads" style={sectionStyle}>
            <Title level={2}>
              <DownloadOutlined style={{ marginRight: 8, color: '#1890ff' }} />
              文档下载
            </Title>
            <Alert
              type="info"
              showIcon
              message="下面是与当前平台版本配套的使用手册"
              description="点击下载后由浏览器接管文件；后续新增的交付文档也统一放在这里。"
              style={{ marginBottom: 16 }}
            />
            <Row gutter={[16, 16]} style={cardStyle}>
              {downloadableManuals.map((manual) => (
                <Col xs={24} md={8} key={manual.key}>
                  <a href={manual.href} download style={{ color: 'inherit' }}>
                    <Card
                      hoverable
                      style={{ height: '100%' }}
                      title={manual.title}
                      extra={<DownloadOutlined />}
                    >
                      <Paragraph type="secondary" style={{ marginBottom: 0 }}>
                        {manual.description}
                      </Paragraph>
                    </Card>
                  </a>
                </Col>
              ))}
            </Row>
          </div>

          <Divider />

          {/* ========== 默认资产说明 ========== */}
          <div id="default-assets" style={sectionStyle}>
            <Title level={2}>
              <SafetyCertificateOutlined
                style={{ marginRight: 8, color: '#1890ff' }}
              />
              默认资产说明
            </Title>
            <Card style={cardStyle}>
              <Paragraph>
                平台为每位用户预置了一套
                <Text strong>默认资产</Text>
                ，登录后即可直接用于训练与推理，无需自己准备数据。 默认资产对
                <Text strong>所有用户可见但只读</Text>
                （不可删除、改名、上传新版本），由管理员统一维护。
              </Paragraph>
              <Table
                size="small"
                rowKey="key"
                pagination={false}
                dataSource={defaultAssets}
                columns={[
                  { title: '资产', dataIndex: 'asset', key: 'asset' },
                  { title: '名称', dataIndex: 'name', key: 'name' },
                  { title: '类型', dataIndex: 'type', key: 'type' },
                  { title: '用途', dataIndex: 'usage', key: 'usage' },
                ]}
              />
              <Paragraph style={{ marginTop: 16 }}>
                <Text strong>训练时</Text>：进入「发起训练」，选择 「HuggingFace
                image
                classification」方案，基础模型、训练数据集、训练代码可直接选用上面的默认资产，
                提交后即可真实训练；训练代码已通过审核，无需再审批。
              </Paragraph>
              <Paragraph style={{ marginBottom: 0 }}>
                <Text strong>推理时</Text>
                ：训练产出模型后，在「推理工作台」选择该模型与默认推理脚本，即可发起推理。
              </Paragraph>
            </Card>
          </div>

          <Divider />

          {/* ========== 2. 模型管理 ========== */}
          <div id="model" style={sectionStyle}>
            <Title level={2}>
              <DatabaseOutlined style={{ marginRight: 8, color: '#1890ff' }} />
              模型管理
            </Title>

            <Card style={cardStyle} title="功能说明">
              <Paragraph>
                模型管理模块用于维护训练产出的模型资产。每个模型资产可包含多个版本，每次上传为一个版本。支持
                CV（计算机视觉）和 NLP（自然语言处理）两大类模型。
              </Paragraph>
              <Timeline
                items={[
                  {
                    dot: <CloudUploadOutlined style={{ fontSize: 16 }} />,
                    children: (
                      <>
                        <Text strong>上传模型</Text>
                        <br />
                        在模型列表页点击"上传模型"，填写模型名称、版本号、类型、备注和
                        Commit 说明，选择模型文件提交。支持分片断点续传。
                      </>
                    ),
                  },
                  {
                    dot: <FileTextOutlined style={{ fontSize: 16 }} />,
                    children: (
                      <>
                        <Text strong>查看详情</Text>
                        <br />
                        点击模型名称进入详情页，查看所有历史版本、超参配置、代码文件等。
                      </>
                    ),
                  },
                  {
                    dot: <CloudUploadOutlined style={{ fontSize: 16 }} />,
                    children: (
                      <>
                        <Text strong>上传新版本</Text>
                        <br />
                        在详情页点击"上传新版本"，为已有模型资产追加版本。版本号必须大于当前最新版本。
                      </>
                    ),
                  },
                ]}
              />
            </Card>

            <Card style={cardStyle} title="上传约束条件">
              <Alert
                type="warning"
                showIcon
                icon={<WarningOutlined />}
                message="上传前请仔细核对以下约束条件，不符合规范的文件将被拒绝"
                style={{ marginBottom: 16 }}
              />
              <Table
                dataSource={modelConstraints}
                columns={[
                  {
                    title: '约束项',
                    dataIndex: 'constraint',
                    key: 'constraint',
                    width: 160,
                    render: (text: string) => <Text strong>{text}</Text>,
                  },
                  {
                    title: '规范要求',
                    dataIndex: 'specification',
                    key: 'specification',
                    render: (text: string) => (
                      <Text code style={{ wordBreak: 'break-all' }}>
                        {text}
                      </Text>
                    ),
                  },
                  {
                    title: '备注',
                    dataIndex: 'remark',
                    key: 'remark',
                  },
                ]}
                pagination={false}
                size="small"
                style={tableStyle}
              />
            </Card>

            <Card style={cardStyle} title="断点续传说明">
              <Paragraph>
                模型上传采用<Text strong>分片断点续传</Text>机制，单个分片大小为
                5 MB。上传过程中如遇网络中断、浏览器关闭等情况，重新选择
                <Text strong>同一文件</Text>并保持
                <Text strong>模型名称、版本、类型一致</Text>
                后提交，系统自动从上次断点继续上传，无需从头开始。
              </Paragraph>
              <Paragraph>
                如需放弃当前续传记录，可在上传页面点击"清除续传记录"按钮。
              </Paragraph>
            </Card>
          </div>

          <Divider />

          {/* ========== 3. 数据集管理 ========== */}
          <div id="dataset" style={sectionStyle}>
            <Title level={2}>
              <FileTextOutlined style={{ marginRight: 8, color: '#52c41a' }} />
              数据集管理
            </Title>

            <Card style={cardStyle} title="功能说明">
              <Paragraph>
                数据集管理模块支持
                CV（计算机视觉）、NLP（自然语言处理）、点云、多模态、机器人等多种类型数据集的上传与版本管理。每种类型有独立的文件格式和结构要求。
              </Paragraph>
              <Timeline
                items={[
                  {
                    dot: <CloudUploadOutlined style={{ fontSize: 16 }} />,
                    children: (
                      <>
                        <Text strong>上传数据集</Text>
                        <br />
                        选择数据集类型，填写名称、版本号、版本描述，上传数据文件。
                        CV
                        可多选文件/文件夹，点云/多模态/NLP/机器人仅支持单文件。
                      </>
                    ),
                  },
                  {
                    dot: <FileTextOutlined style={{ fontSize: 16 }} />,
                    children: (
                      <>
                        <Text strong>查看详情与预览</Text>
                        <br />
                        点击数据集名称查看版本历史。CV
                        和点云数据集支持在线预览。
                      </>
                    ),
                  },
                  {
                    dot: <CloudUploadOutlined style={{ fontSize: 16 }} />,
                    children: (
                      <>
                        <Text strong>上传新版本</Text>
                        <br />
                        为已有数据集资产追加新版本数据。版本号须大于当前最新版本。
                      </>
                    ),
                  },
                ]}
              />
            </Card>

            <Card style={cardStyle} title="各类型数据集规范">
              <Table
                dataSource={datasetConstraints}
                columns={[
                  {
                    title: '约束项',
                    dataIndex: 'constraint',
                    key: 'constraint',
                    width: 180,
                    render: (text: string) => <Text strong>{text}</Text>,
                  },
                  {
                    title: '规范要求',
                    dataIndex: 'specification',
                    key: 'specification',
                    render: (text: string) => (
                      <Text code style={{ wordBreak: 'break-all' }}>
                        {text}
                      </Text>
                    ),
                  },
                  {
                    title: '备注',
                    dataIndex: 'remark',
                    key: 'remark',
                  },
                ]}
                pagination={false}
                size="small"
                style={tableStyle}
              />
            </Card>

            <Card style={cardStyle} title="CV 标注格式说明">
              <Descriptions bordered size="small" column={{ xs: 1, sm: 2 }}>
                <Descriptions.Item label="NONE">
                  纯图片，无标注文件
                </Descriptions.Item>
                <Descriptions.Item label="FOLDER_CLASSIFICATION">
                  按文件夹名称分类，每个文件夹为一个类别
                </Descriptions.Item>
                <Descriptions.Item label="YOLO">
                  YOLO 格式标注（.txt 文件，每行：class_id cx cy w h）
                </Descriptions.Item>
                <Descriptions.Item label="COCO">
                  COCO JSON 格式标注文件
                </Descriptions.Item>
                <Descriptions.Item label="VOC">
                  PASCAL VOC XML 格式标注文件
                </Descriptions.Item>
                <Descriptions.Item label="CSV">
                  CSV 表格格式标注
                </Descriptions.Item>
                <Descriptions.Item label="MASK">
                  掩码图标注（语义分割）
                </Descriptions.Item>
                <Descriptions.Item label="LABELME">
                  LabelMe JSON 格式标注文件
                </Descriptions.Item>
                <Descriptions.Item label="OTHER">
                  其他自定义标注格式
                </Descriptions.Item>
              </Descriptions>
            </Card>

            <Card style={cardStyle} title="多模态数据集目录结构">
              <Title level={5}>AUTO_DIRECTORY 模式（推荐）</Title>
              <Paragraph>
                zip
                根目录直接为样本子目录，每个子目录代表一个样本。目录名即为样本
                ID（externalId），无需额外索引文件。
              </Paragraph>
              <pre
                style={{
                  background: '#1e1e1e',
                  color: '#d4d4d4',
                  padding: '16px 20px',
                  borderRadius: 8,
                  fontSize: 13,
                  lineHeight: 1.8,
                  fontFamily: 'Consolas, Monaco, monospace',
                }}
              >
                {`multimodal_dataset.zip
├── scene_001/              ← 样本目录（目录名 = externalId）
│   ├── image.jpg           ← 数据文件
│   ├── lidar.pcd
│   └── annotations/        ← 标注目录
│       └── labels.json
├── scene_002/
│   ├── image.jpg
│   └── annotations/
│       └── labels.json
└── ...
`}
              </pre>

              <Title level={5} style={{ marginTop: 16 }}>
                MANIFEST 模式
              </Title>
              <Paragraph>
                zip 内需包含 manifest.json
                索引文件，指定每个样本的文件路径与标注信息。支持严格模式（strictManifest），开启后未被
                manifest 声明的文件会导致导入失败。
              </Paragraph>
            </Card>
          </div>

          <Divider />

          {/* ========== 4. 训练调度 ========== */}
          <div id="training" style={sectionStyle}>
            <Title level={2}>
              <ThunderboltOutlined
                style={{ marginRight: 8, color: '#faad14' }}
              />
              训练调度
            </Title>

            <Card style={cardStyle} title="创建训练任务">
              <Paragraph>
                任务列表页用于查看和管理你发起的全部训练任务。在训练代码列表中选择已审核通过的代码版本，点击「发起训练」进入任务创建页，配置训练参数（包括超参、资源需求等）后提交，任务进入调度队列。任务列表会展示任务的名称、模型、数据集、状态、进度等，点击任务名称可进入详情页查看实时进度条与训练指标曲线（loss、accuracy
                等）。
              </Paragraph>
              <Alert
                type="info"
                showIcon
                message="提示"
                description="创建训练任务前，请确保所需的模型和数据集已上传并就绪。"
              />

              <Title level={5} style={{ marginTop: 16 }}>
                动态训练进度与指标记录
              </Title>
              <Paragraph>
                平台会逐行读取训练脚本的标准输出，识别以{' '}
                <Text code>TSS_EVENT </Text>
                开头、其后跟随合法 JSON
                的事件行，据此实时刷新任务进度条并绘制训练指标曲线。想让平台展示任务的过程进度与训练指标，需要在
                <Text strong>你自己的训练代码</Text>中按以下约定打印事件：
              </Paragraph>
              <ul style={{ lineHeight: 2 }}>
                <li>
                  <Text strong>进度事件</Text>：{' '}
                  <Text code>{'{"type":"progress","progress":0~100}'}</Text>
                  —— progress 是 0~100 的完成度百分比（不是 epoch
                  序号），平台会将其映射到训练阶段的进度区间并显示在进度条上。
                </li>
                <li>
                  <Text strong>指标事件</Text>：{' '}
                  <Text code>
                    {'{"type":"metric","step":步数,"metrics":{...}}'}
                  </Text>
                  —— 每次记录一个或多个<Text strong>数值型</Text>指标（如{' '}
                  <Text code>train_loss</Text>、<Text code>val_accuracy</Text>
                  ），step 用于绘制过程曲线；不传 step 时平台会自动递增。
                </li>
                <li>
                  每条事件必须<Text strong>独占一行</Text>、以{' '}
                  <Text code>TSS_EVENT </Text>
                  开头，且打印后立即刷新（flush），例如{' '}
                  <Text code>
                    print("TSS_EVENT " + json.dumps(payload), flush=True)
                  </Text>
                  。
                </li>
              </ul>

              <Title level={5} style={{ marginTop: 8 }}>
                示例代码
              </Title>
              <pre
                style={{
                  background: '#1e1e1e',
                  color: '#d4d4d4',
                  padding: '16px 20px',
                  borderRadius: 8,
                  fontSize: 13,
                  lineHeight: 1.8,
                  fontFamily: 'Consolas, Monaco, monospace',
                }}
              >
                {`import json

def event(payload: dict) -> None:
    """向平台上报一条 TSS_EVENT 进度/指标事件，打印后立即 flush。"""
    print("TSS_EVENT " + json.dumps(payload, ensure_ascii=False), flush=True)

total_epochs = 10
for epoch in range(1, total_epochs + 1):
    loss = train_one_epoch(epoch)          # 替换为你的训练逻辑
    # 动态进度：progress 为 0~100 的完成度百分比（不是 epoch 序号）
    event({"type": "progress", "progress": round(epoch * 100 / total_epochs)})
    # 指标记录：带 step 的指标事件，平台据此绘制训练过程曲线
    event({"type": "metric", "step": epoch, "metrics": {"train_loss": loss}})
event({"type": "progress", "progress": 100})
`}
              </pre>
              <Paragraph style={{ marginTop: 12 }}>
                训练结束后，建议把最终指标写入 <Text code>metrics.json</Text>
                （如 <Text code>train_loss</Text>、
                <Text code>val_accuracy</Text>
                等），便于任务详情页展示完整结果。
              </Paragraph>
            </Card>

            <Card style={cardStyle} title="训练代码管理">
              <Paragraph>
                训练代码管理模块用于上传和管理训练脚本。上传后需经过审核（自动或人工），审核通过后方可在创建训练任务时选用。
              </Paragraph>

              <Title level={5}>上传约束条件</Title>
              <Table
                dataSource={trainingCodeConstraints}
                columns={[
                  {
                    title: '约束项',
                    dataIndex: 'constraint',
                    key: 'constraint',
                    width: 160,
                    render: (text: string) => <Text strong>{text}</Text>,
                  },
                  {
                    title: '规范要求',
                    dataIndex: 'specification',
                    key: 'specification',
                    render: (text: string) => (
                      <Text code style={{ wordBreak: 'break-all' }}>
                        {text}
                      </Text>
                    ),
                  },
                  {
                    title: '备注',
                    dataIndex: 'remark',
                    key: 'remark',
                  },
                ]}
                pagination={false}
                size="small"
                style={tableStyle}
              />

              <Title level={5} style={{ marginTop: 16 }}>
                审核流程
              </Title>
              <Timeline
                items={[
                  {
                    color: 'blue',
                    children: (
                      <>
                        <Text strong>上传</Text> → 用户上传训练代码 zip 包
                      </>
                    ),
                  },
                  {
                    color: 'orange',
                    children: (
                      <>
                        <Text strong>校验</Text> →{' '}
                        后端自动校验包结构与入口脚本，标记 validationStatus
                      </>
                    ),
                  },
                  {
                    color: 'purple',
                    children: (
                      <>
                        <Text strong>审核</Text> →{' '}
                        审核关闭（DIRECT_PASS）时基础校验通过后直接批准；自动审核（STANDARD_REVIEW）按风险策略处理并由人工兜底；仅人工审核（MANUAL_ONLY）时全部进入“待审核”
                      </>
                    ),
                  },
                  {
                    color: 'green',
                    children: (
                      <>
                        <Text strong>就绪</Text> → 审核通过后状态变为
                        APPROVED，可在训练代码列表中选用
                      </>
                    ),
                  },
                ]}
              />
            </Card>

            <Card style={cardStyle} title="算力状态监控">
              <Paragraph>
                算力状态页面展示各 GPU 服务器的实时资源使用情况，包括 CPU / 内存
                / GPU
                利用率、显存占用、磁盘使用率，以及运行中任务与待启动任务等。点击服务器卡片可进入详情页查看资源使用趋势曲线。
              </Paragraph>
              <Descriptions bordered size="small" column={1}>
                <Descriptions.Item label="普通用户">
                  仅可<Text strong>查看</Text>
                  各服务器的资源使用情况与运行中的任务列表，不提供任何变更操作。
                </Descriptions.Item>
                <Descriptions.Item label="超级管理员">
                  除查看外，可对服务器进行<Text strong>启用 / 禁用</Text>
                  操作（禁用后该服务器不再分配新的训练任务，已在运行的任务不受影响），并可纳管、删除服务器以及调整待启动任务的排队顺序。
                </Descriptions.Item>
              </Descriptions>
            </Card>

            <Card style={cardStyle} title="全局排队">
              <Paragraph>
                全局排队页面用于跨服务器查看所有等待资源的训练任务，按所需
                <Text strong>资源池类型</Text>分组展示（如 CPU 池、GPU
                池、自定义池）。该页面<Text strong>仅超级管理员可见</Text>。
              </Paragraph>
              <ul style={{ lineHeight: 2 }}>
                <li>
                  不同资源池（CPU / GPU / 其他）的任务
                  <Text strong>互不竞争</Text>
                  ，各自独立排队；组内顺序决定谁先获得该池空闲资源。
                </li>
                <li>
                  超级管理员可按资源池类型，在对应池内对待分配任务进行
                  <Text strong>上移 / 下移</Text>
                  以调整调度顺序，也可取消任务的排队。
                </li>
                <li>
                  调整只在同一资源池内生效；任务获得资源后由调度器自动分配节点启动。
                </li>
              </ul>
            </Card>
          </div>

          <Divider />

          {/* ========== 5. 模型推理 ========== */}
          <div id="inference" style={sectionStyle}>
            <Title level={2}>
              <ExperimentOutlined
                style={{ marginRight: 8, color: '#722ed1' }}
              />
              模型推理
            </Title>

            <Card style={cardStyle} title="推理工作台">
              <Paragraph>
                推理工作台提供在线模型推理能力，支持上传推理脚本并选择已注册的模型进行推理。适用于模型效果验证、A/B
                对比测试等场景。
              </Paragraph>
              <Paragraph>
                推理结果可在工作台内直接查看，支持结果导出与对比分析。
              </Paragraph>
            </Card>
          </div>

          <Divider />

          {/* ========== 6. 系统管理 ========== */}
          <div id="system" style={sectionStyle}>
            <Title level={2}>
              <SettingOutlined style={{ marginRight: 8, color: '#595959' }} />
              系统管理
            </Title>

            <Card style={cardStyle} title="角色与权限">
              <Descriptions bordered size="small" column={1}>
                <Descriptions.Item label="超级管理员 (super_admin)">
                  全部权限：用户管理、管理员管理、操作日志（含
                  IP）、系统配置、训练方案管理、资源节点管理、数据导出
                </Descriptions.Item>
                <Descriptions.Item label="普通管理员 (normal_admin)">
                  用户管理、操作日志查看（不含 IP）、日志导出
                </Descriptions.Item>
                <Descriptions.Item label="普通用户 (user)">
                  模型/数据集/训练/推理的基本使用、算力状态查看、个人操作日志
                </Descriptions.Item>
              </Descriptions>
            </Card>

            <Card style={cardStyle} title="新增账号与 API 权限">
              <Paragraph>
                管理员新增或恢复账号后，页面会显示后端随机生成的临时密码。该明文只显示这一次，请立即通过安全方式交给用户；如果遗失，请走密码重置流程，不要尝试公共默认密码。
              </Paragraph>
              <Paragraph>
                超级管理员可在“系统管理 → 用户管理”的“API
                权限”入口，按模型、数据集、训练代码与方案、训练任务、推理任务、系统管理与审计六个功能组，单独禁用或恢复某个用户，也可设置该用户在该功能组的并发上限。恢复立即生效，无需重启或重新登录；已有运行任务不会被删除。
              </Paragraph>
              <Alert
                showIcon
                type="warning"
                message="用户级设置只会收紧原有角色权限，不会把普通用户提升成管理员"
              />
            </Card>

            <Card style={cardStyle} title="系统配置">
              <Paragraph>
                系统配置页面（仅超级管理员可访问）可分别控制训练代码审核和自动审核。开启自动审核后，低风险代码可自动通过、阻断项可自动拒绝，其余仍需管理员处理；关闭自动审核后，所有新代码进入人工待审。
              </Paragraph>
            </Card>

            <div id="training-plan-yaml">
              <Card style={cardStyle} title="自定义训练方案 YAML">
                <Alert
                  showIcon
                  type="info"
                  message="仅超级管理员可以发布和停用训练方案"
                  description="模型、数据集和训练方案分别上传、分别保存。训练方案不会改变已有资产，只在发起训练时按 acceptedSpecIds 筛选兼容的模型和数据集。"
                  style={{ marginBottom: 16 }}
                />

                <Title level={4}>五分钟上手</Title>
                <Timeline
                  items={[
                    {
                      children: (
                        <span>
                          进入
                          <Link to="/system/training-plans">
                            系统管理 → 训练方案管理
                          </Link>
                          ，可以在线编写 YAML，也可以上传本地文件。
                        </span>
                      ),
                    },
                    {
                      children:
                        '新建时可下载模板后粘贴到在线编辑器；修改已有方案时点击“编辑新版本”，先提高 version。不要写密码、Token 或任意脚本。',
                    },
                    {
                      children:
                        '在线内容或上传文件都必须点击“预览并校验”。预览只校验，不会保存或替换当前方案。',
                    },
                    {
                      children:
                        '按页面给出的错误路径修改后重新校验。只有显示“可以发布”时，按钮才会解锁。',
                    },
                    {
                      children:
                        '核对方案 ID、版本、镜像、模型/数据集规范、资源上限、差异和 SHA-256，再确认发布。',
                    },
                  ]}
                />

                <Title level={4}>YAML 到底哪些是固定的？</Title>
                <Paragraph>
                  YAML
                  的冒号、缩进、数组等是通用语法；字段名称和含义由本平台固定，不是全世界通用。
                  未知字段会被拒绝，不能自己随意增加字段来执行代码或管理
                  Kubernetes。
                </Paragraph>
                <Descriptions bordered size="small" column={1}>
                  <Descriptions.Item label="身份字段">
                    <Text code>
                      schemaVersion / id / version / displayName / category
                    </Text>
                    ：决定方案身份、版本和 CV/NLP/OTHER 导航类别。
                  </Descriptions.Item>
                  <Descriptions.Item label="输入规范">
                    <Text code>inputs.*.acceptedSpecIds</Text>
                    ：只引用平台已注册的模型和数据集规范，不引用某个具体资产
                    ID。
                  </Descriptions.Item>
                  <Descriptions.Item label="执行与参数">
                    <Text code>execution / parameters</Text>
                    ：入口、允许传入的参数、默认值和上下限，必须符合平台白名单。
                  </Descriptions.Item>
                  <Descriptions.Item label="运行环境">
                    <Text code>runtimes</Text>
                    ：只允许平台批准的不可变镜像、CPU/GPU 资源档位和拉取策略。
                  </Descriptions.Item>
                  <Descriptions.Item label="结果与安全">
                    <Text code>outputs / security</Text>
                    ：声明结果文件和安全边界；不能申请特权容器、hostPath
                    或任意网络访问。
                  </Descriptions.Item>
                </Descriptions>

                <Title level={4} style={{ marginTop: 16 }}>
                  发布、停用和恢复规则
                </Title>
                <ul style={{ lineHeight: 2 }}>
                  <li>
                    同一方案的新版 version 必须高于所有内置和在线历史版本。
                  </li>
                  <li>
                    同一 ID + version
                    发布后内容不可原地覆盖；需要修改时提高版本号。
                  </li>
                  <li>
                    预览返回的 SHA-256
                    必须与发布文件一致，文件变化后必须重新预览。
                  </li>
                  <li>
                    停用只阻止新任务选择，不删除历史方案，不影响已创建任务、日志和结果。
                  </li>
                  <li>
                    再次发布完全相同的已停用
                    YAML，可恢复该版本；不同内容不能借此覆盖。
                  </li>
                </ul>

                <Alert
                  showIcon
                  type="warning"
                  message="当前只提供经过验证的 CV/CPU 模板"
                  description="NLP 资产规范和私有镜像拉取链完成真实验证后再提供 NLP 模板。不要把 CV 模板简单改成 NLP 后强行发布。"
                />
              </Card>
            </div>
          </div>

          <Divider />

          {/* ========== 7. 版本管理规范 ========== */}
          <div id="versioning" style={sectionStyle}>
            <Title level={2}>
              <SafetyCertificateOutlined
                style={{ marginRight: 8, color: '#13c2c2' }}
              />
              版本管理规范
            </Title>

            <Card style={cardStyle}>
              <Title level={4}>版本号格式</Title>
              <Paragraph>
                平台统一使用两种版本号格式（模型与数据集通用）：
              </Paragraph>
              <ul>
                <li>
                  <Text code>vN</Text> 简化格式：如 <Text code>v1</Text>、{' '}
                  <Text code>v2</Text>、<Text code>v3</Text>
                  —— 适用于快速迭代场景
                </li>
                <li>
                  <Text code>vX.Y.Z</Text> 语义化格式：如{' '}
                  <Text code>v1.0.0</Text>、<Text code>v2.1.3</Text>
                  —— 适用于正式发布版本
                </li>
              </ul>

              <Title level={4} style={{ marginTop: 16 }}>
                版本递增规则
              </Title>
              <ul>
                <li>
                  当前最新版本为 <Text code>vN</Text> 风格时，建议下一版本为{' '}
                  <Text code>{'v{N+1}'}</Text>
                </li>
                <li>
                  当前最新版本为 <Text code>vX.Y.Z</Text> 风格时，建议下一版本为{' '}
                  <Text code>{'vX.Y.{Z+1}'}</Text>
                </li>
                <li>
                  跨风格切换（如 v3 → v4.0.0）同样有效，只要新版本严格大于旧版本
                </li>
              </ul>

              <Title level={4} style={{ marginTop: 16 }}>
                版本描述规范
              </Title>
              <Paragraph>
                数据集版本描述（必填，10~2000 字符）建议采用以下模板：
              </Paragraph>
              <pre
                style={{
                  background: '#f6f8fa',
                  padding: '16px 20px',
                  borderRadius: 8,
                  fontSize: 13,
                  lineHeight: 1.8,
                  border: '1px solid #e8e8e8',
                }}
              >
                {`【更新原因】修正上一版本中 200 张图片的标签错误
【更新内容】
  - 新增 500 张场景 X 的标注数据
  - 修正 200 张分类错误标签
  - 统一图片尺寸为 640×640`}
              </pre>
            </Card>
          </div>

          <Divider />

          {/* ========== 8. 常见报错处理 ========== */}
          <div id="errors" style={sectionStyle}>
            <Title level={2}>
              <WarningOutlined style={{ marginRight: 8, color: '#ff4d4f' }} />
              常见报错处理
            </Title>

            <Card style={cardStyle}>
              <Paragraph>
                以下汇总了平台使用过程中常见的报错信息、原因分析及解决方法。如遇到未列出的错误，请联系系统管理员。
              </Paragraph>
              <Collapse
                size="large"
                items={commonErrors.map((item, index) => ({
                  key: String(index),
                  label: (
                    <Space>
                      <Tag color="error">常见</Tag>
                      <Text strong>{item.error}</Text>
                    </Space>
                  ),
                  children: (
                    <>
                      <Descriptions bordered size="small" column={1}>
                        <Descriptions.Item label="错误原因">
                          {item.cause}
                        </Descriptions.Item>
                        <Descriptions.Item label="解决方法">
                          <Text type="success">{item.solution}</Text>
                        </Descriptions.Item>
                      </Descriptions>
                    </>
                  ),
                }))}
              />
            </Card>
          </div>

          <Divider />

          {/* ========== 9. 最佳实践 ========== */}
          <div id="best-practices" style={sectionStyle}>
            <Title level={2}>
              <BookOutlined style={{ marginRight: 8, color: '#1890ff' }} />
              最佳实践
            </Title>

            <Card style={cardStyle}>
              <Collapse
                size="large"
                items={[
                  {
                    key: '1',
                    label: <Text strong>数据上传建议</Text>,
                    children: (
                      <ul style={{ lineHeight: 2 }}>
                        <li>
                          大文件（超过 1
                          GB）建议在稳定的网络环境下上传，充分利用断点续传机制
                        </li>
                        <li>
                          CV 数据集上传前请确认标注格式正确，避免上传后无法解析
                        </li>
                        <li>
                          多模态数据集推荐使用 AUTO_DIRECTORY
                          模式，结构清晰、无需额外索引文件
                        </li>
                        <li>NLP 数据集多个文件请先打包为 .zip 再上传</li>
                        <li>
                          每次上传务必填写详细的版本描述，便于团队协作和后续追溯
                        </li>
                        <li>定期清理不再使用的旧版本数据，释放存储空间</li>
                      </ul>
                    ),
                  },
                  {
                    key: '2',
                    label: <Text strong>模型管理建议</Text>,
                    children: (
                      <ul style={{ lineHeight: 2 }}>
                        <li>
                          模型名称应具有可读性和唯一性，建议包含模型架构和用途信息（如
                          resnet50-imagenet-pretrain）
                        </li>
                        <li>
                          Commit
                          说明应遵循简洁明确的原则，记录本次版本的训练配置或改进点
                        </li>
                        <li>
                          超参信息建议以 JSON 格式完整记录，便于复现训练结果
                        </li>
                        <li>大模型（如千问、LLaMA 等）请打包为 .zip 后上传</li>
                        <li>
                          重要版本建议在备注中标注（如"生产环境使用版本"）
                        </li>
                      </ul>
                    ),
                  },
                  {
                    key: '3',
                    label: <Text strong>训练代码管理建议</Text>,
                    children: (
                      <ul style={{ lineHeight: 2 }}>
                        <li>
                          确保 zip 包内包含训练方案要求的入口脚本（如 train.py）
                        </li>
                        <li>
                          不要将大尺寸数据文件打包进训练代码
                          zip（数据应通过数据集模块管理）
                        </li>
                        <li>代码包命名建议包含版本或日期信息，便于区分</li>
                        <li>人工审核模式下，上传后及时通知管理员进行审核</li>
                      </ul>
                    ),
                  },
                  {
                    key: '4',
                    label: <Text strong>安全与权限建议</Text>,
                    children: (
                      <ul style={{ lineHeight: 2 }}>
                        <li>定期修改登录密码，不使用弱密码</li>
                        <li>不要将个人 Token 分享给他人</li>
                        <li>操作完成及时退出登录，尤其是在公共设备上</li>
                        <li>如发现异常操作记录，立即联系管理员</li>
                      </ul>
                    ),
                  },
                ]}
              />
            </Card>
          </div>

          {/* 底部间距 */}
          <div style={{ height: 80 }} />
        </Col>

        {/* ===== 右侧锚点导航 ===== */}
        <Col xs={0} lg={6} xl={5}>
          <div
            style={{
              position: 'sticky',
              top: 80,
              padding: '16px 0',
            }}
          >
            <Card size="small" title="目录导航" style={{ marginBottom: 16 }}>
              <Anchor
                affix={false}
                offsetTop={96}
                items={[
                  { key: 'overview', href: '#overview', title: '平台概述' },
                  {
                    key: 'document-downloads',
                    href: '#document-downloads',
                    title: '文档下载',
                  },
                  {
                    key: 'default-assets',
                    href: '#default-assets',
                    title: '默认资产说明',
                  },
                  { key: 'model', href: '#model', title: '模型管理' },
                  { key: 'dataset', href: '#dataset', title: '数据集管理' },
                  { key: 'training', href: '#training', title: '训练调度' },
                  { key: 'inference', href: '#inference', title: '模型推理' },
                  { key: 'system', href: '#system', title: '系统管理' },
                  {
                    key: 'training-plan-yaml',
                    href: '#training-plan-yaml',
                    title: '自定义训练方案',
                  },
                  {
                    key: 'versioning',
                    href: '#versioning',
                    title: '版本管理规范',
                  },
                  { key: 'errors', href: '#errors', title: '常见报错处理' },
                  {
                    key: 'best-practices',
                    href: '#best-practices',
                    title: '最佳实践',
                  },
                ]}
              />
            </Card>
            <Card
              size="small"
              style={{ background: '#f6f8fa' }}
              bodyStyle={{ padding: '12px 16px' }}
            >
              <Link to="/api-doc" style={{ fontSize: 13, color: '#666' }}>
                <LinkOutlined style={{ marginRight: 6 }} />
                开发者入口：OpenAPI 文档
              </Link>
              <div style={{ fontSize: 12, color: '#999', marginTop: 6 }}>
                后端接口调试与 API 规范查看
              </div>
            </Card>
          </div>
        </Col>
      </Row>
    </PageContainer>
  );
};

export default UserManual;
