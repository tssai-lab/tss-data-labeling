#!/bin/bash
# TSSAIPlatform-frontend 快速启动脚本

cd "$(dirname "$0")"

echo "=========================================="
echo "TSSAIPlatform-frontend 启动脚本"
echo "=========================================="
echo ""

echo "📦 检查依赖安装状态..."
if [ ! -d "node_modules" ]; then
    echo "⚠️  依赖未安装，正在安装依赖..."
    echo "   这可能需要 5-10 分钟，请耐心等待..."
    npm install --registry=https://registry.npmmirror.com
    if [ $? -ne 0 ]; then
        echo "❌ 依赖安装失败，请检查："
        echo "   1. 网络连接是否正常"
        echo "   2. Node.js 版本是否 >= 20.0.0（当前: $(node --version)）"
        echo "   3. npm 是否正常工作"
        exit 1
    fi
    echo "✅ 依赖安装完成"
else
    echo "✅ 依赖已安装"
fi

echo ""
echo "🚀 启动开发服务器..."
echo "   访问地址: http://localhost:8000"
echo "   按 Ctrl+C 停止服务器"
echo ""

npm run start:dev
