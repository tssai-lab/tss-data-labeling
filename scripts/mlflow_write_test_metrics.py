#!/usr/bin/env python3
"""
MLflow 测试数据写入脚本
用于在无训练系统时，向独立 MLflow 服务写入模拟训练指标，供前端任务详情页测试图表展示。

使用前请先启动 MLflow 服务：
  mlflow server --backend-store-uri sqlite:///mlflow.db --host 0.0.0.0 --port 5000

运行本脚本：
  python scripts/mlflow_write_test_metrics.py

运行后会打印 run_id，复制到前端任务详情页的「输入 MLflow Run ID」输入框中即可查看图表。
"""
import os

# 禁用代理，避免请求 localhost:5000 时走代理导致 502
os.environ.pop("http_proxy", None)
os.environ.pop("https_proxy", None)
os.environ.pop("HTTP_PROXY", None)
os.environ.pop("HTTPS_PROXY", None)
os.environ["NO_PROXY"] = "localhost,127.0.0.1"

import mlflow

# 指向独立 MLflow 服务（与 config/proxy.ts 中 target 一致）
MLFLOW_TRACKING_URI = "http://localhost:5000"

mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

with mlflow.start_run(run_name="test-run-frontend") as run:
    run_id = run.info.run_id
    print(f"\n✅ 已写入测试指标，run_id = {run_id}\n")
    print("请复制上述 run_id，在前端任务详情页（/task/detail/1）的输入框中粘贴并点击「加载指标」。\n")

    # 模拟 10 轮训练的指标变化
    for step in range(1, 11):
        mlflow.log_metric("train_loss", 2.0 - step * 0.15, step=step)
        mlflow.log_metric("val_accuracy", step * 0.08, step=step)
        mlflow.log_metric("val_mAP50", step * 0.07, step=step)
        mlflow.log_metric("box_loss", 1.5 - step * 0.1, step=step)
        mlflow.log_metric("cls_loss", 0.8 - step * 0.05, step=step)

    print("已写入指标: train_loss, val_accuracy, val_mAP50, box_loss, cls_loss")
