# Frontend production Dockerfile (static build served by nginx)
FROM node:20-alpine AS build

WORKDIR /app

# Build arguments for environment variables
ARG VITE_API_URL=/api
ARG VITE_FIREBASE_API_KEY
ARG VITE_FIREBASE_AUTH_DOMAIN
ARG VITE_FIREBASE_PROJECT_ID
ARG VITE_FIREBASE_STORAGE_BUCKET
ARG VITE_FIREBASE_MESSAGING_SENDER_ID
ARG VITE_FIREBASE_APP_ID
ARG VITE_PAYMENT_CUTOFF_DATE=2025-04-29
ARG VITE_DEFAULT_LOCALE=ka

# Set environment variables for build
ENV VITE_API_URL=${VITE_API_URL}
ENV VITE_FIREBASE_API_KEY=${VITE_FIREBASE_API_KEY}
ENV VITE_FIREBASE_AUTH_DOMAIN=${VITE_FIREBASE_AUTH_DOMAIN}
ENV VITE_FIREBASE_PROJECT_ID=${VITE_FIREBASE_PROJECT_ID}
ENV VITE_FIREBASE_STORAGE_BUCKET=${VITE_FIREBASE_STORAGE_BUCKET}
ENV VITE_FIREBASE_MESSAGING_SENDER_ID=${VITE_FIREBASE_MESSAGING_SENDER_ID}
ENV VITE_FIREBASE_APP_ID=${VITE_FIREBASE_APP_ID}
ENV VITE_PAYMENT_CUTOFF_DATE=${VITE_PAYMENT_CUTOFF_DATE}
ENV VITE_DEFAULT_LOCALE=${VITE_DEFAULT_LOCALE}

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
  CMD curl -sf http://localhost/ >/dev/null 2>&1 || exit 1
