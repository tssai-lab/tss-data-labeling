import { Link } from '@umijs/max';
import { Card, Typography } from 'antd';
import WorkspaceLayout from './WorkspaceLayout';
import ui from './workspaceUi.less';

export default function Help() {
  return (
    <WorkspaceLayout>
      <header className={ui.pageHeader}>
        <div>
          <h1>标注使用说明</h1>
          <p>了解数据导入、标注配置、审核和成果导出的基本流程。</p>
        </div>
      </header>
      <Card title="使用指南" style={{ maxWidth: 1000 }}>
        <Typography.Paragraph>
          <Link to="/labeling">返回项目列表</Link>
        </Typography.Paragraph>
        <Typography.Title level={4}>导入数据</Typography.Title>
        <Typography.Paragraph>
          上传 ZIP 数据包。任务 JSON 中的 data
          保存每个样本的数据或文件引用；同包图片、CSV 作为引用资源。 一份 CSV
          就是一条完整曲线时，选择“文件作为一个样本”；CSV
          每行描述一个独立样本时，选择“每行一个样本”。
          只有需要合并明细或调整字段时才使用“高级整理”。
        </Typography.Paragraph>
        <Typography.Title level={4}>模板与字段绑定</Typography.Title>
        <Typography.Paragraph>
          在项目“标注设置”选择模板，编辑标签并预览。XML 的 value="$csv" 读取样本
          data.csv； timeColumn 指定时间列，Channel 的 column
          指定数值列。字段就是数据中的名称，必须与配置一致。
          模板是可修改的配置起点，保存后生成项目规范版本。
        </Typography.Paragraph>
        <Typography.Title level={4}>时序坐标与缩放</Typography.Title>
        <Typography.Paragraph>
          横轴显示配置的时间列；未配置时间列时使用数据点序号。数字时间不会自动变成小时。
          日期时间通过 timeFormat 解析，timeDisplayFormat 控制显示格式。
          纵轴显示各 Channel 的数值，单位由数据和 Channel 的 units 决定。
          下方概览用于选择时间范围，拖动选择区域调整范围。多通道可能分别显示纵轴。
        </Typography.Paragraph>
        <Typography.Title level={4}>标注区域与关联</Typography.Title>
        <Typography.Paragraph>
          选择标签后在数据上创建区域。右侧“标注区域”用于选择和管理区域，“信息”显示所选区域的属性，
          “历史”显示编辑记录。“关联”用于建立两个区域之间的关系，是否需要使用由项目规范决定。
          面板支持拖动和调整大小，浏览器会记住布局。
        </Typography.Paragraph>
        <Typography.Title level={4}>保存与提交</Typography.Title>
        <Typography.Paragraph>
          “保存草稿”保留未完成内容；“标记完成并保存”检查当前样本；任务包内样本全部完成后可提交任务包。
          需求方核查后可确认、修订或再次发布。导出读取已确认并固定的成果。
        </Typography.Paragraph>
        <Typography.Title level={4}>资源读取与开放控件</Typography.Title>
        <Typography.Paragraph>
          资源读取依赖登录状态、项目权限和任务领取关系。读取失败时先检查是否登录、任务是否仍由自己领取，
          以及数据字段绑定是否正确。当前开放计算机视觉和时序分析，具体控件以配置校验为准；
          不支持在标注配置中执行用户上传的 React 或 JavaScript 代码。
        </Typography.Paragraph>
      </Card>
    </WorkspaceLayout>
  );
}
