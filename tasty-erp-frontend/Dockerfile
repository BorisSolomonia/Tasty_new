# Frontend production Dockerfile (static build served by nginx)
FROM node:20-alpine AS build

WORKDIR /app

ARG VITE_API_URL=/api
ENV VITE_API_URL=${VITE_API_URL}

COPY tasty-erp-frontend/package.json tasty-erp-frontend/package-lock.json* ./
RUN npm ci

COPY tasty-erp-frontend/ ./
RUN npm run build

FROM nginx:1.27-alpine

RUN apk add --no-cache curl

COPY tasty-erp-frontend/nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD curl -f http://localhost/ >/dev/null 2>&1 || exit 1
