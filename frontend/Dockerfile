FROM node:24.18.0-alpine3.23 AS build
ARG VITE_OIDC_AUTHORITY=http://localhost:8081/realms/cellarbridge
ARG VITE_OIDC_CLIENT_ID=cellarbridge-console
ENV VITE_OIDC_AUTHORITY=${VITE_OIDC_AUTHORITY}
ENV VITE_OIDC_CLIENT_ID=${VITE_OIDC_CLIENT_ID}
RUN corepack enable
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY contracts /workspace/contracts
COPY frontend ./
RUN pnpm generate:api && pnpm build

FROM cgr.dev/chainguard/nginx:latest@sha256:e4ff957080737c90a9ecfeaa40e3d19ea9d687e9cacda2f2a031c75ffcdd72b7
COPY frontend/nginx.conf /etc/nginx/conf.d/nginx.default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
EXPOSE 8080
