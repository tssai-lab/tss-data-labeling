# syntax=docker/dockerfile:1

FROM node:20-bookworm-slim AS build

WORKDIR /app
ENV HUSKY=0

COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit

COPY . .
RUN npm run build && test -s dist/index.html

FROM nginx:1.28-alpine AS runtime

ARG VCS_REF=unknown
LABEL org.opencontainers.image.source="https://github.com/tssai-lab/TSSAIPlatform" \
      org.opencontainers.image.revision="${VCS_REF}"

ENV BACKEND_UPSTREAM=http://backend:8080 \
    MLFLOW_UPSTREAM=http://mlflow:5000 \
    NGINX_ENVSUBST_FILTER="^(BACKEND_UPSTREAM|MLFLOW_UPSTREAM)$"

COPY deploy/nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY --from=build /app/dist/ /usr/share/nginx/html/

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
