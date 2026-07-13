FROM node:24.18.0-alpine3.23 AS build
RUN corepack enable
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY contracts /workspace/contracts
COPY frontend ./
RUN pnpm generate:api && pnpm build

FROM nginx:1.29.4-alpine
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html
EXPOSE 80
