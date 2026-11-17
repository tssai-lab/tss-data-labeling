export default {
  enableHotkeys: {
    newUI: {
      title: "启用标签快捷键",
      description: "使用快捷键快速选择标签",
    },
    description: "启用标签快捷键",
    onChangeEvent: "toggleHotkeys",
    defaultValue: true,
  },
  enableTooltips: {
    newUI: {
      title: "在工具提示中显示快捷键",
      description: "在工具和操作的提示中显示快捷键",
    },
    description: "显示快捷键提示",
    onChangeEvent: "toggleTooltips",
    checked: "",
    defaultValue: false,
  },
  enableLabelTooltips: {
    newUI: {
      title: "在标签上显示快捷键",
      description: "在标签旁显示对应的快捷键",
    },
    description: "显示标签快捷键提示",
    onChangeEvent: "toggleLabelTooltips",
    defaultValue: true,
  },
  showLabels: {
    newUI: {
      title: "显示区域标签",
      description: "显示标注区域的标签名称",
    },
    description: "在区域内显示标签",
    onChangeEvent: "toggleShowLabels",
    defaultValue: false,
  },
  continuousLabeling: {
    newUI: {
      title: "创建区域后保持标签选中",
      description: "使用当前选中的标签连续创建区域",
    },
    description: "创建区域后保持标签选中",
    onChangeEvent: "toggleContinuousLabeling",
    defaultValue: false,
  },
  selectAfterCreate: {
    newUI: {
      title: "创建区域后自动选中",
      description: "自动选中新创建的标注区域",
    },
    description: "创建后选中区域",
    onChangeEvent: "toggleSelectAfterCreate",
    defaultValue: false,
  },
  showLineNumbers: {
    newUI: {
      tags: "文本",
      title: "显示行号",
      description: "通过行号定位和引用文本",
    },
    description: "为文本显示行号",
    onChangeEvent: "toggleShowLineNumbers",
    defaultValue: false,
  },
  preserveSelectedTool: {
    newUI: {
      tags: "图像",
      title: "保持工具选中",
      description: "切换样本时保留当前选中的工具",
    },
    description: "记住选中的工具",
    onChangeEvent: "togglepreserveSelectedTool",
    defaultValue: true,
  },
  enableSmoothing: {
    newUI: {
      tags: "图像",
      title: "缩放时平滑像素",
      description: "放大图像时平滑显示像素",
    },
    description: "缩放时启用图像平滑",
    onChangeEvent: "toggleSmoothing",
    defaultValue: true,
  },
  invertedZoom: {
    newUI: {
      tags: "图像",
      title: "反转缩放方向",
      description: "反转鼠标滚轮的缩放方向",
    },
    description: "启用反向缩放",
    onChangeEvent: "toggleInvertedZoom",
    defaultValue: false,
  },
};
