ARG INFERENCE_BASE_IMAGE=tss/yolo-trainer-cpu:latest
FROM ${INFERENCE_BASE_IMAGE}

USER root
WORKDIR /app

ARG PYPI_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
ARG TORCH_CPU_WHEEL_URL=https://mirrors.aliyun.com/pytorch-wheels/cpu/torch-2.6.0%2Bcpu-cp311-cp311-linux_x86_64.whl
ARG TORCHVISION_CPU_WHEEL_URL=https://mirrors.aliyun.com/pytorch-wheels/cpu/torchvision-0.21.0%2Bcpu-cp311-cp311-linux_x86_64.whl

RUN useradd -r -u 10001 -m worker 2>/dev/null || true \
    && mkdir -p /workspace/job \
    && chown -R worker:worker /workspace

COPY requirements.txt .
COPY requirements-ml-cpu.txt .
RUN python -m pip uninstall -y opencv-python opencv-contrib-python opencv-contrib-python-headless || true \
    && python -m pip install --no-cache-dir -r requirements.txt \
    -i "${PYPI_INDEX_URL}" \
    && python -m pip install --no-cache-dir \
    -i "${PYPI_INDEX_URL}" \
    "${TORCH_CPU_WHEEL_URL}" \
    "${TORCHVISION_CPU_WHEEL_URL}" \
    && python -m pip install --no-cache-dir -r requirements-ml-cpu.txt \
    -i "${PYPI_INDEX_URL}" \
    && python -m pip uninstall -y \
    opencv-python opencv-contrib-python opencv-contrib-python-headless \
    && python -m pip install --no-cache-dir --force-reinstall --no-deps \
    opencv-python-headless==4.11.0.86 \
    -i "${PYPI_INDEX_URL}"

COPY infer_worker.py .

USER 10001

# Verify the libraries are available without relying on the build user's
# ~/.local directory. Production jobs replace HOME with a writable /tmp path.
RUN HOME=/tmp/tss-runtime-home PYTHONNOUSERSITE=1 python -c \
    "import cv2, numpy, torch, transformers, ultralytics; assert int(numpy.__version__.split('.')[0]) < 2"

ENTRYPOINT ["python", "/app/infer_worker.py"]
