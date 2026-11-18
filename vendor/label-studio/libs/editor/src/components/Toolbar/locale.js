const labels = {
  'Pan Image': '平移图片', 'Zoom In': '放大图片', 'Zoom Out': '缩小图片',
  'Zoom to fit': '适应窗口', 'Zoom to actual size': '原始尺寸',
  'Rectangle': '矩形框', 'Bounding Box': '矩形框', 'Polygon': '多边形',
  'Brush': '画笔', 'Eraser': '橡皮擦', 'Key Point': '关键点',
  'Keypoint': '关键点', 'Rotate Left': '向左旋转', 'Rotate Right': '向右旋转',
  'Move': '移动标注', 'Contrast': '对比度', 'Brightness': '亮度', 'Bitmask': '像素掩码', 'Magic Wand': '魔棒',
};
export const toolLabel = label => typeof label === 'string' ? labels[label] || label : label;
