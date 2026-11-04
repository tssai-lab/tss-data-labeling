ARG TRAINING_WORKER_BASE_IMAGE=python:3.11-slim
FROM ${TRAINING_WORKER_BASE_IMAGE}

USER root
WORKDIR /app

RUN if ! getent passwd 10001 >/dev/null; then useradd -r -u 10001 -m worker; fi \
    && mkdir -p /workspace/job \
    && chown -R 10001 /workspace

COPY requirements.txt .
ARG PYTORCH_CPU_INDEX_URL=https://download.pytorch.org/whl/cpu
ARG INSTALL_PYTORCH_CPU=true
RUN if [ "${INSTALL_PYTORCH_CPU}" = "true" ]; then \
      pip install --no-cache-dir \
        torch==2.5.1 \
        torchvision==0.20.1 \
        --index-url "${PYTORCH_CPU_INDEX_URL}"; \
    fi
RUN pip install --no-cache-dir -r requirements.txt \
    -i https://pypi.tuna.tsinghua.edu.cn/simple

COPY --chmod=0644 train.py .

USER 10001

ENTRYPOINT ["python", "/app/train.py"]
