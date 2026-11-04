ARG PYTHON_BASE_IMAGE=python:3.11-slim
FROM ${PYTHON_BASE_IMAGE}

WORKDIR /app
COPY app.py /app/app.py

RUN useradd --system --uid 10001 --create-home mlflow \
    && mkdir -p /mlflow \
    && chown -R mlflow:mlflow /mlflow

USER 10001
EXPOSE 5000
ENTRYPOINT ["python", "/app/app.py"]
