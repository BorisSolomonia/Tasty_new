# GCP VM Deployment Guide
### Full-stack apps with Docker, Caddy, GitHub Actions & GCP Artifact Registry

> **Who this is for:** Rookie developers and LLMs that need to deploy a web application to a GCP VM using the same battle-tested patterns used by the Tasty ERP system. Every step is explained. Every tool is introduced. Every command is real — copy-paste ready.

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Concepts and Tools Explained](#2-concepts-and-tools-explained)
3. [Part A — One-Time GCP Setup](#part-a--one-time-gcp-setup)
   - [A1. Create a GCP Project](#a1-create-a-gcp-project)
   - [A2. Enable Required APIs](#a2-enable-required-apis)
   - [A3. Create a VM Instance](#a3-create-a-vm-instance)
   - [A4. Reserve a Static IP](#a4-reserve-a-static-ip)
   - [A5. Open Firewall Ports](#a5-open-firewall-ports)
   - [A6. Create Artifact Registry Repository](#a6-create-artifact-registry-repository)
   - [A7. Create a GitHub Actions Service Account](#a7-create-a-github-actions-service-account)
   - [A8. Set Up GCP Secret Manager](#a8-set-up-gcp-secret-manager)
4. [Part B — VM Bootstrap (Run Once on the VM)](#part-b--vm-bootstrap-run-once-on-the-vm)
   - [B1. SSH Into the VM](#b1-ssh-into-the-vm)
   - [B2. Run the Auto-Install Bootstrap Script](#b2-run-the-auto-install-bootstrap-script)
   - [B3. Create Docker Networks and Volumes](#b3-create-docker-networks-and-volumes)
   - [B4. Create the Deployment Directory](#b4-create-the-deployment-directory)
5. [Part C — Your Application's Files](#part-c--your-applications-files)
   - [C1. Dockerfile — Backend Service](#c1-dockerfile--backend-service)
   - [C2. Dockerfile — Frontend (React/Vite)](#c2-dockerfile--frontend-reactvite)
   - [C3. Nginx Config for Frontend](#c3-nginx-config-for-frontend)
   - [C4. compose.production.yml](#c4-composeproductionyml)
   - [C5. Caddyfile.production](#c5-caddyfileproduction)
   - [C6. .dockerignore](#c6-dockerignore)
   - [C7. .env.example](#c7-envexample)
6. [Part D — GitHub Actions CI/CD Pipeline](#part-d--github-actions-cicd-pipeline)
   - [D1. Repository Secrets to Configure](#d1-repository-secrets-to-configure)
   - [D2. ci.yml — Pull Request Tests](#d2-ciyml--pull-request-tests)
   - [D3. deploy.yml — Full Build and Deploy](#d3-deployyml--full-build-and-deploy)
7. [Part E — Multi-Domain Routing (Multiple Apps, One VM)](#part-e--multi-domain-routing-multiple-apps-one-vm)
8. [Part F — Secrets Management](#part-f--secrets-management)
9. [Part G — Rollback Procedure](#part-g--rollback-procedure)
10. [Part H — Monitoring and Logs](#part-h--monitoring-and-logs)
11. [Part I — Troubleshooting Runbook](#part-i--troubleshooting-runbook)
12. [Part J — Adapting This Guide to Different Stacks](#part-j--adapting-this-guide-to-different-stacks)
13. [Quick Reference Card](#quick-reference-card)

---

## 1. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│  Developer pushes to GitHub (master branch)                      │
└──────────────────────────────────┬───────────────────────────────┘
                                   │ triggers
                                   ▼
┌──────────────────────────────────────────────────────────────────┐
│  GitHub Actions Runner (ubuntu-latest, managed by GitHub)        │
│                                                                  │
│  1. Run tests (mvn test / npm test)                              │
│  2. Build Docker images (multi-stage)                            │
│  3. Push images → GCP Artifact Registry                          │
│  4. SSH into VM → pull images → docker compose up               │
└──────────────────────────────────┬───────────────────────────────┘
                                   │ SSH deploy
                                   ▼
┌──────────────────────────────────────────────────────────────────┐
│  GCP VM  (e2-medium, us-central1-c, Ubuntu 22.04)               │
│                                                                  │
│  /home/username/apps/my-app/                                     │
│    ├── compose.production.yml                                    │
│    ├── Caddyfile.production                                      │
│    ├── .env           ← fetched from GCP Secret Manager          │
│    └── secrets/
│        └── firebase-sa.json  ← fetched from GCP Secret Manager  │
│                                                                  │
│  Docker Containers (docker network: web)                         │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────────┐   │
│  │  caddy   │  │ frontend │  │ backend  │  │ other-service│   │
│  │ :80/:443 │  │  :80     │  │  :8081   │  │   :8082      │   │
│  └────┬─────┘  └──────────┘  └──────────┘  └──────────────┘   │
│       │  routes by domain/path to containers on Docker network  │
│       │  auto HTTPS via Let's Encrypt                           │
└───────┴──────────────────────────────────────────────────────────┘
                                   │
                                   ▼
                         Internet / Browser
```

**Key principle**: Caddy is the only container that has ports 80 and 443 exposed to the internet. All other containers are on an internal Docker network (`web`) and are reachable only by Caddy — or each other — by container name.

---

## 2. Concepts and Tools Explained

| Tool | What it is | Why we use it |
|------|-----------|---------------|
| **Docker** | Packages your app + its dependencies into a portable container | "Works on my machine" becomes "works everywhere" |
| **Docker Compose** | Runs multiple containers together from a YAML file | Define your whole stack (frontend + backend + db + proxy) in one file |
| **Caddy** | A web server and reverse proxy with automatic HTTPS | Replaces nginx for routing + gets Let's Encrypt certs automatically — zero config |
| **GitHub Actions** | CI/CD automation built into GitHub | Runs tests, builds Docker images, deploys to your VM on every push |
| **GCP Artifact Registry** | Docker image storage on GCP | Stores versioned images; VM pulls from here instead of re-building on the VM |
| **GCP Secret Manager** | Encrypted secrets storage | Keeps `.env` files and credentials out of Git and off your laptop |
| **GCP VM** | A Linux server in Google's cloud | Runs your containers 24/7 |
| **Multi-stage Docker builds** | Build step in one container, run in another | Final image is tiny (no build tools, no source code) |
| **Static IP** | A fixed IP address for your VM | DNS can point to it reliably; doesn't change on VM restart |

---

## Part A — One-Time GCP Setup

> Do this once per GCP project. Takes ~30 minutes.

### A1. Create a GCP Project

```bash
# Install gcloud CLI first: https://cloud.google.com/sdk/docs/install
# Then log in:
gcloud auth login

# Create a new project (replace MY_PROJECT_ID with something unique)
gcloud projects create MY_PROJECT_ID --name="My App"

# Set it as default
gcloud config set project MY_PROJECT_ID

# Link billing account (required for VMs)
# List your billing accounts:
gcloud billing accounts list

# Link (replace BILLING_ACCOUNT_ID with yours, e.g. 012345-ABCDEF-789012)
gcloud billing projects link MY_PROJECT_ID \
  --billing-account=BILLING_ACCOUNT_ID
```

### A2. Enable Required APIs

```bash
# Enable all APIs needed for this deployment pattern
gcloud services enable \
  compute.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com \
  iam.googleapis.com \
  cloudresourcemanager.googleapis.com
```

### A3. Create a VM Instance

```bash
# Create a VM optimized for running Docker containers
# e2-medium = 2 vCPU, 4GB RAM — good starting point for 2-4 services
# For heavier Java apps: use e2-standard-2 (2 vCPU, 8GB RAM)

gcloud compute instances create my-app-vm \
  --project=MY_PROJECT_ID \
  --zone=us-central1-c \
  --machine-type=e2-medium \
  --image-family=ubuntu-2204-lts \
  --image-project=ubuntu-os-cloud \
  --boot-disk-size=50GB \
  --boot-disk-type=pd-ssd \
  --scopes=https://www.googleapis.com/auth/cloud-platform \
  --tags=http-server,https-server

# IMPORTANT: --scopes=cloud-platform allows the VM to access
# Artifact Registry and Secret Manager without extra key setup.
# This is the cleanest approach for VMs.
```

**Machine type guide:**
| Machine type | vCPU | RAM | Best for |
|---|---|---|---|
| `e2-micro` | 0.25 | 1 GB | Static sites, very light apps |
| `e2-small` | 0.5 | 2 GB | Single lightweight service |
| `e2-medium` | 1 | 4 GB | 2-3 services, Node.js + frontend |
| `e2-standard-2` | 2 | 8 GB | Java Spring Boot + frontend + services |
| `e2-standard-4` | 4 | 16 GB | Multiple heavy Java services |

### A4. Reserve a Static IP

Without a static IP, your VM's external IP changes every time it's restarted, breaking DNS.

```bash
# Reserve a static external IP in the same region as your VM
gcloud compute addresses create my-app-static-ip \
  --region=us-central1

# See the reserved IP address
gcloud compute addresses describe my-app-static-ip \
  --region=us-central1 \
  --format="get(address)"

# Assign the static IP to your VM
gcloud compute instances delete-access-config my-app-vm \
  --access-config-name="External NAT" \
  --zone=us-central1-c

gcloud compute instances add-access-config my-app-vm \
  --access-config-name="External NAT" \
  --address=YOUR_RESERVED_IP \
  --zone=us-central1-c
```

> **DNS setup**: Once you have the static IP, go to your domain registrar (Namecheap, Cloudflare, etc.) and create an `A` record pointing `yourdomain.com` → `YOUR_RESERVED_IP`. Caddy needs DNS to point to the VM before it can get an SSL certificate.

### A5. Open Firewall Ports

```bash
# Allow HTTP (port 80) — needed for Let's Encrypt domain verification and HTTP traffic
gcloud compute firewall-rules create allow-http \
  --allow=tcp:80 \
  --target-tags=http-server \
  --description="Allow HTTP traffic"

# Allow HTTPS (port 443) — for SSL traffic
gcloud compute firewall-rules create allow-https \
  --allow=tcp:443 \
  --target-tags=https-server \
  --description="Allow HTTPS traffic"

# The VM already has SSH (port 22) open by default in GCP.
# Do NOT open ports 8081, 8082, etc. to the internet — Caddy handles routing internally.
```

### A6. Create Artifact Registry Repository

GCP Artifact Registry stores your Docker images. Think of it as Docker Hub, but private and within GCP.

```bash
# Create a Docker repository in Artifact Registry
gcloud artifacts repositories create my-app \
  --repository-format=docker \
  --location=us-central1 \
  --description="Docker images for my-app"

# Your image URLs will look like:
# us-central1-docker.pkg.dev/MY_PROJECT_ID/my-app/service-name:tag
```

### A7. Create a GitHub Actions Service Account

GitHub Actions needs credentials to push images to Artifact Registry and deploy to the VM.

```bash
# Create a dedicated service account for GitHub Actions
gcloud iam service-accounts create github-actions-sa \
  --display-name="GitHub Actions Service Account"

# Grant it permission to push Docker images to Artifact Registry
gcloud projects add-iam-policy-binding MY_PROJECT_ID \
  --member="serviceAccount:github-actions-sa@MY_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

# Grant it permission to read secrets from Secret Manager
gcloud projects add-iam-policy-binding MY_PROJECT_ID \
  --member="serviceAccount:github-actions-sa@MY_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"

# Grant it permission to authenticate Docker in Artifact Registry
gcloud projects add-iam-policy-binding MY_PROJECT_ID \
  --member="serviceAccount:github-actions-sa@MY_PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"

# Create and download a JSON key for this service account
gcloud iam service-accounts keys create github-actions-key.json \
  --iam-account=github-actions-sa@MY_PROJECT_ID.iam.gserviceaccount.com

# The contents of github-actions-key.json go into the GitHub secret GCP_SA_KEY
# (See Part D1 for GitHub secrets setup)
# IMPORTANT: Delete this file after uploading to GitHub. Never commit it.
cat github-actions-key.json
rm github-actions-key.json
```

### A8. Set Up GCP Secret Manager

Store all sensitive configuration in Secret Manager. The VM fetches them at deploy time.

```bash
# Create the .env secret (your app's environment variables)
# First create a file locally with your env vars:
cat > /tmp/my-app.env << 'EOF'
DATABASE_URL=postgresql://user:password@host:5432/dbname
API_KEY=your-secret-api-key
JWT_SECRET=very-long-random-string-here
SOME_OTHER_VAR=value
EOF

# Upload it as a secret
gcloud secrets create env \
  --data-file=/tmp/my-app.env \
  --project=MY_PROJECT_ID

rm /tmp/my-app.env

# If you have a Firebase service account JSON (or any credentials file):
gcloud secrets create firebase-sa \
  --data-file=./path/to/firebase-sa.json \
  --project=MY_PROJECT_ID

# To update a secret later:
gcloud secrets versions add env \
  --data-file=/tmp/updated.env \
  --project=MY_PROJECT_ID

# To view a secret (careful — this shows plaintext):
gcloud secrets versions access latest --secret="env" --project=MY_PROJECT_ID
```

---

## Part B — VM Bootstrap (Run Once on the VM)

### B1. SSH Into the VM

```bash
# Using gcloud (recommended — handles keys automatically)
gcloud compute ssh my-app-vm --zone=us-central1-c --project=MY_PROJECT_ID

# Or using standard SSH with your key:
ssh -i ~/.ssh/your-key username@YOUR_VM_IP
```

### B2. Run the Auto-Install Bootstrap Script

Copy-paste this entire script into your SSH session. It checks for each tool and installs it if missing.

```bash
#!/bin/bash
# VM Bootstrap Script — checks and installs all required tools
# Run this once on a fresh VM. Safe to re-run (idempotent).

set -e

echo "======================================"
echo " Tasty ERP VM Bootstrap"
echo " Checking and installing dependencies"
echo "======================================"

# --- Helper functions ---
check_and_install() {
  local name="$1"
  local check_cmd="$2"
  local install_cmd="$3"

  if eval "$check_cmd" &>/dev/null; then
    echo "✅ $name already installed: $(eval "$check_cmd" 2>/dev/null || echo 'ok')"
  else
    echo "📦 Installing $name..."
    eval "$install_cmd"
    echo "✅ $name installed successfully"
  fi
}

# --- Update system packages ---
echo ""
echo "--- Updating system packages ---"
sudo apt-get update -qq
sudo apt-get upgrade -y -qq

# --- Install basic tools ---
echo ""
echo "--- Installing basic tools ---"
sudo apt-get install -y -qq \
  curl \
  wget \
  git \
  unzip \
  jq \
  ca-certificates \
  gnupg \
  lsb-release \
  apt-transport-https \
  software-properties-common

echo "✅ Basic tools installed"

# --- Docker ---
echo ""
echo "--- Checking Docker ---"
if ! command -v docker &>/dev/null; then
  echo "📦 Installing Docker..."
  # Official Docker installation method
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg | \
    sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg

  echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] \
    https://download.docker.com/linux/ubuntu $(lsb_release -cs) stable" | \
    sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

  sudo apt-get update -qq
  sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin

  # Allow current user to run docker without sudo
  sudo usermod -aG docker "$USER"

  # Start and enable Docker service
  sudo systemctl start docker
  sudo systemctl enable docker

  echo "✅ Docker installed: $(docker --version)"
  echo "⚠️  NOTE: You need to log out and back in for docker group to take effect"
  echo "          OR run: newgrp docker"
else
  echo "✅ Docker already installed: $(docker --version)"
fi

# --- Docker Compose (plugin) ---
echo ""
echo "--- Checking Docker Compose ---"
if ! docker compose version &>/dev/null; then
  echo "📦 Installing Docker Compose plugin..."
  sudo apt-get install -y docker-compose-plugin
  echo "✅ Docker Compose installed: $(docker compose version)"
else
  echo "✅ Docker Compose already installed: $(docker compose version)"
fi

# --- gcloud CLI ---
echo ""
echo "--- Checking gcloud CLI ---"
if ! command -v gcloud &>/dev/null; then
  echo "📦 Installing gcloud CLI..."
  curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg | \
    sudo gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg

  echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] \
    https://packages.cloud.google.com/apt cloud-sdk main" | \
    sudo tee /etc/apt/sources.list.d/google-cloud-sdk.list > /dev/null

  sudo apt-get update -qq
  sudo apt-get install -y google-cloud-cli

  echo "✅ gcloud installed: $(gcloud --version | head -1)"
else
  echo "✅ gcloud already installed: $(gcloud --version | head -1)"
fi

# --- Configure gcloud to use VM's service account (no key file needed) ---
# The VM was created with --scopes=cloud-platform, so gcloud works automatically
echo ""
echo "--- Configuring gcloud project ---"
gcloud config set project MY_PROJECT_ID 2>/dev/null || true
echo "✅ gcloud project set"

# --- Configure Docker to authenticate with GCP Artifact Registry ---
echo ""
echo "--- Configuring Docker for Artifact Registry ---"
gcloud auth configure-docker us-central1-docker.pkg.dev --quiet
echo "✅ Docker configured for Artifact Registry"

# --- Node.js (optional, needed if you run scripts on the VM) ---
echo ""
echo "--- Checking Node.js ---"
if ! command -v node &>/dev/null; then
  echo "📦 Installing Node.js 20..."
  curl -fsSL https://deb.nodesource.com/setup_20.x | sudo -E bash -
  sudo apt-get install -y nodejs
  echo "✅ Node.js installed: $(node --version)"
else
  echo "✅ Node.js already installed: $(node --version)"
fi

# --- Java (optional, needed if you run Java directly without Docker) ---
# Uncomment if needed:
# echo ""
# echo "--- Checking Java ---"
# if ! command -v java &>/dev/null; then
#   echo "📦 Installing OpenJDK 17..."
#   sudo apt-get install -y openjdk-17-jdk
#   echo "✅ Java installed: $(java --version)"
# else
#   echo "✅ Java already installed: $(java --version)"
# fi

# --- Summary ---
echo ""
echo "======================================"
echo " Bootstrap Complete!"
echo "======================================"
echo " Docker:         $(docker --version)"
echo " Docker Compose: $(docker compose version)"
echo " gcloud:         $(gcloud --version | head -1)"
echo " Node.js:        $(node --version 2>/dev/null || echo 'not installed')"
echo ""
echo " NEXT STEPS:"
echo " 1. If Docker was just installed, run: newgrp docker"
echo " 2. Run: B3 script to create networks and volumes"
echo " 3. Run: B4 script to create deployment directory"
echo "======================================"
```

> **After the script completes**: If Docker was just installed, you need to apply the group change without logging out: run `newgrp docker` or close and reopen your SSH session.

### B3. Create Docker Networks and Volumes

```bash
# Create the external Docker network that all containers share
# "external: true" in compose files means this network must exist before compose runs
docker network create web

# Create persistent volume for Caddy's TLS certificate storage
# This must survive container restarts — if Caddy loses certs, it requests new ones
# and may hit Let's Encrypt rate limits
docker volume create caddy_data

# Verify
docker network ls | grep web
docker volume ls | grep caddy_data
```

### B4. Create the Deployment Directory

```bash
# Create the directory where Docker Compose files and secrets will live
mkdir -p /home/$USER/apps/my-app/secrets
mkdir -p /home/$USER/apps/my-app/backups

# Set permissions so only you can read the secrets directory
chmod 700 /home/$USER/apps/my-app/secrets

echo "Deployment directory created at: /home/$USER/apps/my-app"
```

---

## Part C — Your Application's Files

These files live in your Git repository and are uploaded to the VM during deployment.

### C1. Dockerfile — Backend Service

This is the pattern for a **Java/Spring Boot** service. Adapt the build command for your language.

```dockerfile
# backend-service/Dockerfile
# Multi-stage build: stage 1 compiles, stage 2 is the final lean image

# ── Stage 1: Build ──────────────────────────────────────────────
FROM maven:3.9.11-eclipse-temurin-17 AS build

WORKDIR /workspace

# Copy pom files first — Docker caches this layer separately.
# Dependencies are only re-downloaded when pom.xml changes, not on every code change.
COPY pom.xml .
COPY backend-service/pom.xml backend-service/
# If you have shared modules, copy their poms too:
# COPY common/pom.xml common/

# Download dependencies (cached layer)
RUN mvn dependency:go-offline -pl backend-service -am -B -q || \
    mvn dependency:go-offline -pl backend-service -am -B -q

# Copy source code (this layer re-runs on every code change, but deps are cached above)
# COPY common/src common/src   # shared modules first
COPY backend-service/src backend-service/src

# Build the JAR (retry once for transient Maven repo issues)
RUN mvn -B -DskipTests -pl backend-service -am package || \
    mvn -B -DskipTests -pl backend-service -am package

# ── Stage 2: Runtime ────────────────────────────────────────────
# Use a minimal JRE image — no build tools, no source code, tiny image
FROM eclipse-temurin:17-jre-alpine

# Install curl for health checks
RUN apk add --no-cache curl

# Create a non-root user for security (never run as root in production)
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# Copy only the built JAR from the build stage
COPY --from=build /workspace/backend-service/target/*.jar app.jar

# JVM tuning for containers:
# UseContainerSupport: JVM respects Docker memory limits (critical!)
# MaxRAMPercentage=75.0: use up to 75% of container memory for the heap
# java.security.egd: faster startup (avoids /dev/random blocking)
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

**For Node.js/Express backend:**
```dockerfile
# backend-service/Dockerfile (Node.js)
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY . .

FROM node:20-alpine
RUN apk add --no-cache curl
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
WORKDIR /app
COPY --from=build /app .
EXPOSE 3001
CMD ["node", "server.js"]
```

**For Python/FastAPI backend:**
```dockerfile
# backend-service/Dockerfile (Python)
FROM python:3.12-slim AS build
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir --prefix=/install -r requirements.txt

FROM python:3.12-slim
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
RUN useradd -m -u 1000 appuser
USER appuser
WORKDIR /app
COPY --from=build /install /usr/local
COPY --chown=appuser:appuser . .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
```

### C2. Dockerfile — Frontend (React/Vite)

```dockerfile
# frontend/Dockerfile
# Stage 1: Build the static files
FROM node:20-alpine AS build

WORKDIR /app

# Build-time environment variables (baked into the bundle)
# These are NOT secrets — they're visible in the browser.
ARG VITE_API_URL=/api
ARG VITE_SOME_PUBLIC_KEY
ENV VITE_API_URL=${VITE_API_URL}
ENV VITE_SOME_PUBLIC_KEY=${VITE_SOME_PUBLIC_KEY}

# Install dependencies (cached separately from source code)
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci

# Build the app
COPY frontend/ ./
RUN npm run build
# Output is in /app/dist

# Stage 2: Serve with nginx
FROM nginx:1.27-alpine
RUN apk add --no-cache curl

# Custom nginx config (handles SPA routing — see C3)
COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf

# Copy built files
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s --retries=3 \
  CMD curl -sf http://localhost/ >/dev/null 2>&1 || exit 1
```

### C3. Nginx Config for Frontend

This config handles Single Page Apps (React, Vue, Angular) where the browser handles routing but nginx just returns `index.html` for all paths.

```nginx
# frontend/nginx.conf
server {
    listen 80;
    server_name _;

    root /usr/share/nginx/html;
    index index.html;

    # SPA routing: if the file doesn't exist, serve index.html
    # This allows React Router / Vue Router to handle navigation
    location / {
        try_files $uri /index.html;
    }

    # Cache static assets aggressively (they have content-hash filenames from Vite)
    location ~* \.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }

    # Don't cache index.html (so users get the latest deploy immediately)
    location = /index.html {
        add_header Cache-Control "no-cache, no-store, must-revalidate";
    }
}
```

### C4. compose.production.yml

This file defines which containers run on the VM and how they're configured.

```yaml
# compose.production.yml
# Deploy with: TAG=abc123 docker compose -f compose.production.yml up -d
# Or: TAG=latest docker compose -f compose.production.yml up -d

services:

  # ── Frontend ─────────────────────────────────────────────────────
  frontend:
    # Image from GCP Artifact Registry — pulled at deploy time
    image: us-central1-docker.pkg.dev/MY_PROJECT_ID/MY_REPO/my-app-frontend:${TAG:-latest}
    container_name: my-app-frontend
    restart: unless-stopped   # restart on crash, but not on manual docker stop
    expose:
      - "80"                  # expose inside Docker network only, NOT to internet
    networks:
      - web
    deploy:
      resources:
        limits:
          memory: 256M        # nginx serving static files needs very little memory
          cpus: "0.25"
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost/"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 15s

  # ── Backend Service ───────────────────────────────────────────────
  backend:
    image: us-central1-docker.pkg.dev/MY_PROJECT_ID/MY_REPO/my-app-backend:${TAG:-latest}
    container_name: my-app-backend
    restart: unless-stopped
    env_file:
      - .env                  # loaded from GCP Secret Manager at deploy time
    environment:
      - SERVER_PORT=8081
      - SPRING_PROFILES_ACTIVE=production   # or NODE_ENV=production
    expose:
      - "8081"
    networks:
      - web
    deploy:
      resources:
        limits:
          memory: 768M        # Java services need more memory for JVM
          cpus: "0.5"
    volumes:
      # Mount credentials file read-only
      - ./secrets/service-account.json:/secrets/service-account.json:ro
    healthcheck:
      test: ["CMD", "curl", "-sf", "http://localhost:8081/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s       # Java/Spring needs ~60s to start

  # ── Caddy (Reverse Proxy + TLS) ───────────────────────────────────
  caddy:
    image: caddy:2-alpine
    container_name: caddy
    restart: unless-stopped
    ports:
      - "80:80"               # HTTP — Caddy redirects to HTTPS + handles ACME challenges
      - "443:443"             # HTTPS — serves your app securely
    extra_hosts:
      # On Linux, this maps host.docker.internal to the Docker bridge gateway IP.
      # Allows Caddy to reach containers or services running directly on the host
      # (e.g., a second app bound to port 8090 on the host).
      - "host.docker.internal:host-gateway"
    volumes:
      - ./Caddyfile.production:/etc/caddy/Caddyfile:ro  # config (read-only)
      - caddy_data:/data        # Let's Encrypt certs — MUST persist across restarts
      - caddy_config:/config    # Caddy's internal config cache
      - caddy_logs:/var/log/caddy
    networks:
      - web
    deploy:
      resources:
        limits:
          memory: 128M
          cpus: "0.25"
    healthcheck:
      test: ["CMD", "wget", "-q", "--spider", "http://localhost/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 15s
    depends_on:
      - frontend
      - backend

# ── Volumes ──────────────────────────────────────────────────────────
volumes:
  caddy_data:
    external: true  # IMPORTANT: must be created manually before first deploy
                    # (see B3). Keeps TLS certs across compose down/up cycles.
  caddy_config:     # Not external — recreated on deploy is fine
  caddy_logs:

# ── Networks ─────────────────────────────────────────────────────────
networks:
  web:
    external: true  # IMPORTANT: must be created manually (see B3).
                    # external means multiple compose stacks can share it.
```

### C5. Caddyfile.production

Caddy's routing configuration. The magic of Caddy: if you use a real domain name instead of `:80`, it **automatically gets HTTPS certificates from Let's Encrypt** — no manual certificate setup needed.

```caddy
# Caddyfile.production
#
# IMPORTANT: DNS for your domain must point to this VM BEFORE Caddy starts.
# Caddy performs an HTTP challenge with Let's Encrypt to get the cert.
# If DNS doesn't resolve to this VM, Caddy can't get the cert and will fail.
#
# For local/IP-only access with no domain, use :80 instead of your domain name.

# ── Your main app domain ─────────────────────────────────────────────
yourdomain.com {
    # Caddy automatically handles HTTPS here.
    # No certificate config needed — it's all automatic.

    # Access logging (JSON format for easy parsing/shipping to logging services)
    log {
        output file /var/log/caddy/access.log {
            roll_size 10mb
            roll_keep 5
        }
        format json
    }

    # ── CORS for API routes ──────────────────────────────────────
    # These named matchers (@api, @options) allow reuse across multiple handle blocks
    @api {
        path /api/*
    }
    handle @api {
        @options method OPTIONS
        handle @options {
            header Access-Control-Allow-Origin "*"
            header Access-Control-Allow-Methods "GET, POST, PUT, DELETE, OPTIONS"
            header Access-Control-Allow-Headers "Content-Type, Authorization, X-Requested-With"
            header Access-Control-Max-Age "86400"
            respond 204
        }
        header Access-Control-Allow-Origin "*"
        header Access-Control-Allow-Methods "GET, POST, PUT, DELETE, OPTIONS"
        header Access-Control-Allow-Headers "Content-Type, Authorization, X-Requested-With"
    }

    # ── API routing to backend services ─────────────────────────
    # Routes /api/users* to the backend container named "backend" on port 8081.
    # Container name resolution works because Caddy and backend are on the same Docker network.
    handle /api/* {
        reverse_proxy backend:8081 {
            lb_try_duration 30s      # retry for up to 30s if backend is starting
            lb_try_interval 1s
            health_uri /health       # Caddy pings this to check backend is alive
            health_interval 30s
            health_timeout 5s
        }
    }

    # ── Health check endpoint ─────────────────────────────────────
    # Used by deployment script to verify Caddy is up after deploy
    respond /health 200

    # ── Frontend (catch-all) ──────────────────────────────────────
    # Everything that doesn't match /api/* goes to the frontend nginx container
    handle {
        reverse_proxy frontend:80 {
            lb_try_duration 10s
            lb_try_interval 1s
        }
    }
}

# ── Second app on same VM, different domain ──────────────────────────
# This is how you host multiple apps on one VM.
# The portfolio/other app runs as a separate container on host port 8090.
# host.docker.internal resolves to the host via the extra_hosts setting in compose.
anotherdomain.com {
    reverse_proxy host.docker.internal:8090 {
        lb_try_duration 10s
        lb_try_interval 1s
    }
}

# ── Redirect www to non-www ───────────────────────────────────────────
www.yourdomain.com {
    redir https://yourdomain.com{uri} permanent
}
```

**Caddy named matchers explained:**
```
@api { path /api/* }  ← defines a named matcher "@api"
handle @api { ... }   ← only runs for requests matching @api
handle { ... }        ← catch-all (runs for everything that didn't match above)
```

### C6. .dockerignore

Exclude files from the Docker build context. A smaller build context = faster builds and no accidentally copied secrets.

```dockerignore
# .dockerignore
# These files are excluded from the Docker build context

# Version control (not needed inside image)
.git
.github
.gitignore

# Local editor/OS files
.vscode/
.idea/
**/.DS_Store
**/Thumbs.db

# Build outputs (will be rebuilt inside Docker)
**/target/
**/build/
**/dist/

# Node modules (will be reinstalled inside Docker)
**/node_modules/
**/.vite/

# Local secrets (NEVER copy into image)
.env
.env.local
.env.production
secrets/
**/*.pem
**/*.key
**/*-sa.json
**/*-service-account*.json

# Logs
**/*.log
logs/

# Test outputs
**/test-results/
**/coverage/

# Documentation (save space)
*.md
docs/
```

### C7. .env.example

Template for environment variables. **Commit this file**. Never commit the actual `.env`.

```bash
# .env.example
# Copy to .env and fill in real values
# Store the real .env in GCP Secret Manager — see Part F

# ── Database ─────────────────────────────────────────────────────────
DATABASE_URL=postgresql://user:password@host:5432/dbname
DATABASE_POOL_SIZE=10

# ── Authentication ────────────────────────────────────────────────────
JWT_SECRET=change-me-to-a-long-random-string
JWT_EXPIRY_HOURS=24

# ── External APIs ─────────────────────────────────────────────────────
THIRD_PARTY_API_KEY=
THIRD_PARTY_API_URL=https://api.example.com

# ── App Configuration ─────────────────────────────────────────────────
APP_ENV=production
LOG_LEVEL=INFO
SERVER_PORT=8081

# ── Service URLs (for inter-service communication within Docker network) ─
# These use Docker container names as hostnames — they resolve inside the network
OTHER_SERVICE_URL=http://other-service:8082
```

---

## Part D — GitHub Actions CI/CD Pipeline

### D1. Repository Secrets to Configure

Go to your GitHub repo → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**

| Secret Name | Value | How to get it |
|---|---|---|
| `GCP_SA_KEY` | Contents of `github-actions-key.json` | Created in A7 |
| `VM_HOST` | Your VM's static IP address | From A4 |
| `VM_SSH_USER` | Your VM username (e.g. `borissolomonia`) | From GCP console |
| `VM_SSH_KEY` | Your SSH private key | `cat ~/.ssh/id_rsa` or generate new |
| `VITE_API_URL` | `/api` | Frontend env var |
| *(any other env vars for frontend build)* | | |

**Generate an SSH key pair for GitHub Actions:**
```bash
# Generate a dedicated key pair (don't use your personal key)
ssh-keygen -t ed25519 -C "github-actions-deploy" -f ~/.ssh/github_actions_deploy -N ""

# Add the PUBLIC key to the VM's authorized_keys
# Run this from your local machine:
gcloud compute ssh my-app-vm --zone=us-central1-c \
  --command="echo '$(cat ~/.ssh/github_actions_deploy.pub)' >> ~/.ssh/authorized_keys"

# The PRIVATE key goes into GitHub secret VM_SSH_KEY:
cat ~/.ssh/github_actions_deploy
# Copy the entire output (including BEGIN/END lines) into the GitHub secret
```

### D2. ci.yml — Pull Request Tests

```yaml
# .github/workflows/ci.yml
# Runs on every pull request — tests only, no deployment
name: CI

on:
  pull_request:
    branches: [master, main]

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      # ── Java/Maven project ────────────────────────────────────────
      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven       # cache ~/.m2 between runs — speeds up dependency download

      - name: Run tests
        run: mvn clean verify -B    # -B = batch mode (less verbose output)

      # ── Node.js project (uncomment if needed) ────────────────────
      # - name: Set up Node.js
      #   uses: actions/setup-node@v4
      #   with:
      #     node-version: '20'
      #     cache: 'npm'
      #     cache-dependency-path: frontend/package-lock.json
      #
      # - name: Install dependencies
      #   run: npm ci
      #   working-directory: frontend
      #
      # - name: Run tests
      #   run: npm test
      #   working-directory: frontend

      # Upload test results as artifacts for viewing in GitHub UI
      - name: Upload test results
        uses: actions/upload-artifact@v4
        if: always()       # upload even if tests fail
        with:
          name: test-results
          path: |
            **/target/surefire-reports/
            **/target/failsafe-reports/
          retention-days: 7
```

### D3. deploy.yml — Full Build and Deploy

This is the main CI/CD pipeline. Study each job and step — they all have comments explaining why.

```yaml
# .github/workflows/deploy.yml
name: Deploy My App

on:
  push:
    branches: [master]      # deploys automatically on every push to master
  workflow_dispatch:         # allows manual trigger from GitHub UI
    inputs:
      tag:
        description: 'Image tag to deploy (default: latest commit SHA)'
        required: false
        type: string
      rollback:
        description: 'Rollback to previous deployment'
        required: false
        type: boolean
        default: false

# Prevent two deploys running simultaneously — second waits for first to finish
concurrency:
  group: production-deploy
  cancel-in-progress: false

env:
  PROJECT_ID: MY_PROJECT_ID                           # your GCP project ID
  REGION: us-central1                                  # GCP region
  AR_REPO: MY_REPO                                     # Artifact Registry repo name
  IMAGE_PREFIX: us-central1-docker.pkg.dev/MY_PROJECT_ID/MY_REPO
  DEPLOY_DIR: /home/YOUR_USERNAME/apps/my-app         # directory on VM

jobs:

  # ─────────────────────────────────────────────────────────────────
  # Job 1: Tests — must pass before any build happens
  # ─────────────────────────────────────────────────────────────────
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: maven

      - name: Run tests
        run: mvn clean verify -B

  # ─────────────────────────────────────────────────────────────────
  # Job 2: Build backend images (parallel matrix)
  # Each service builds in its own runner simultaneously
  # ─────────────────────────────────────────────────────────────────
  build-backend:
    needs: test                    # only runs if tests pass
    if: ${{ !inputs.rollback }}    # skip on rollback
    runs-on: ubuntu-latest
    strategy:
      matrix:
        service:
          - name: backend
            dockerfile: backend-service/Dockerfile
          # Add more services here:
          # - name: worker
          #   dockerfile: worker-service/Dockerfile
    steps:
      - uses: actions/checkout@v4

      - name: Authenticate to Google Cloud
        uses: google-github-actions/auth@v2
        with:
          credentials_json: ${{ secrets.GCP_SA_KEY }}

      - name: Configure Docker for Artifact Registry
        run: gcloud auth configure-docker ${{ env.REGION }}-docker.pkg.dev --quiet

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3   # enables advanced Docker build features

      - name: Build and push ${{ matrix.service.name }}
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ${{ matrix.service.dockerfile }}
          push: true
          tags: |
            ${{ env.IMAGE_PREFIX }}/my-app-${{ matrix.service.name }}:${{ github.sha }}
            ${{ env.IMAGE_PREFIX }}/my-app-${{ matrix.service.name }}:latest
          # GitHub Actions cache makes repeat builds ~80% faster
          # It stores Docker layer cache between workflow runs
          cache-from: type=gha,scope=${{ matrix.service.name }}
          cache-to: type=gha,mode=max,scope=${{ matrix.service.name }}

  # ─────────────────────────────────────────────────────────────────
  # Job 3: Build frontend image
  # Separate from backend because it needs env vars injected at build time
  # ─────────────────────────────────────────────────────────────────
  build-frontend:
    needs: test
    if: ${{ !inputs.rollback }}
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Authenticate to Google Cloud
        uses: google-github-actions/auth@v2
        with:
          credentials_json: ${{ secrets.GCP_SA_KEY }}

      - name: Configure Docker for Artifact Registry
        run: gcloud auth configure-docker ${{ env.REGION }}-docker.pkg.dev --quiet

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Build and push frontend
        uses: docker/build-push-action@v5
        with:
          context: .
          file: frontend/Dockerfile
          push: true
          tags: |
            ${{ env.IMAGE_PREFIX }}/my-app-frontend:${{ github.sha }}
            ${{ env.IMAGE_PREFIX }}/my-app-frontend:latest
          # Build-time env vars are baked into the JS bundle at build time
          # These are NOT secrets — they're visible in browser DevTools.
          # If you need truly secret values at runtime, use a backend API instead.
          build-args: |
            VITE_API_URL=/api
            VITE_SOME_PUBLIC_KEY=${{ secrets.VITE_SOME_PUBLIC_KEY }}
          cache-from: type=gha,scope=frontend
          cache-to: type=gha,mode=max,scope=frontend

  # ─────────────────────────────────────────────────────────────────
  # Job 4: Deploy to VM
  # ─────────────────────────────────────────────────────────────────
  deploy:
    needs: [test, build-backend, build-frontend]
    # This condition allows deploy to run even on rollback (where builds are skipped)
    if: >
      always() &&
      needs.test.result == 'success' &&
      (
        (needs.build-backend.result == 'success' && needs.build-frontend.result == 'success') ||
        inputs.rollback == true
      )
    runs-on: ubuntu-latest
    environment: production    # optional: require manual approval in GitHub for production deploys
    steps:
      - uses: actions/checkout@v4

      - name: Authenticate to Google Cloud
        uses: google-github-actions/auth@v2
        with:
          credentials_json: ${{ secrets.GCP_SA_KEY }}

      - name: Set up gcloud
        uses: google-github-actions/setup-gcloud@v2

      # Determine which image tag to deploy
      - name: Determine deployment tag
        id: tag
        run: |
          if [ "${{ inputs.rollback }}" == "true" ]; then
            echo "TAG=rollback" >> $GITHUB_OUTPUT
            echo "ROLLBACK=true" >> $GITHUB_OUTPUT
          elif [ -n "${{ inputs.tag }}" ]; then
            echo "TAG=${{ inputs.tag }}" >> $GITHUB_OUTPUT
            echo "ROLLBACK=false" >> $GITHUB_OUTPUT
          else
            echo "TAG=${{ github.sha }}" >> $GITHUB_OUTPUT
            echo "ROLLBACK=false" >> $GITHUB_OUTPUT
          fi

      # Upload deployment config files to VM
      # These are in your Git repo and get synced on every deploy
      - name: Upload deployment files
        uses: appleboy/scp-action@v0.1.7
        with:
          host: ${{ secrets.VM_HOST }}
          username: ${{ secrets.VM_SSH_USER }}
          key: ${{ secrets.VM_SSH_KEY }}
          source: "compose.production.yml,Caddyfile.production"
          target: ${{ env.DEPLOY_DIR }}
          overwrite: true

      # The main deployment step — runs a shell script on the VM via SSH
      - name: Deploy to VM
        uses: appleboy/ssh-action@v1.0.3
        env:
          DEPLOY_TAG: ${{ steps.tag.outputs.TAG }}
          IS_ROLLBACK: ${{ steps.tag.outputs.ROLLBACK }}
          GCP_PROJECT: ${{ env.PROJECT_ID }}
        with:
          host: ${{ secrets.VM_HOST }}
          username: ${{ secrets.VM_SSH_USER }}
          key: ${{ secrets.VM_SSH_KEY }}
          envs: DEPLOY_TAG,IS_ROLLBACK,GCP_PROJECT
          script: |
            set -e   # exit immediately if any command fails

            cd "${{ env.DEPLOY_DIR }}"

            # Create required directories
            mkdir -p secrets backups

            # ── Handle rollback ────────────────────────────────────
            if [ "$IS_ROLLBACK" == "true" ]; then
              echo "=== ROLLBACK MODE ==="
              if [ -f backups/previous-tag ]; then
                DEPLOY_TAG=$(cat backups/previous-tag)
                echo "Rolling back to: $DEPLOY_TAG"
              else
                echo "ERROR: No previous deployment tag saved. Cannot rollback."
                exit 1
              fi
            fi

            # ── Save current tag for rollback ─────────────────────
            if [ -f .current-tag ]; then
              cp .current-tag backups/previous-tag
            fi
            echo "$DEPLOY_TAG" > .current-tag
            echo "Deploying tag: $DEPLOY_TAG"

            # ── Fetch secrets from GCP Secret Manager ─────────────
            # The VM has cloud-platform scope, so gcloud works without a key file
            echo "Fetching secrets from GCP Secret Manager..."

            gcloud secrets versions access latest \
              --secret="env" \
              --project="$GCP_PROJECT" > .env
            chmod 600 .env   # only owner can read

            # Uncomment if you have a credentials JSON file:
            # gcloud secrets versions access latest \
            #   --secret="service-account" \
            #   --project="$GCP_PROJECT" > secrets/service-account.json
            # chmod 644 secrets/service-account.json

            # ── Ensure Docker infrastructure exists ───────────────
            # These are idempotent — safe to run every deploy
            docker network create web 2>/dev/null || true
            docker volume create caddy_data 2>/dev/null || true

            # ── Configure Docker for Artifact Registry ────────────
            gcloud auth configure-docker us-central1-docker.pkg.dev --quiet

            # ── Pull new images ───────────────────────────────────
            export TAG="$DEPLOY_TAG"
            echo "Pulling images with tag: $DEPLOY_TAG"
            docker compose -f compose.production.yml pull

            # ── Stop current deployment ───────────────────────────
            echo "Stopping current deployment..."
            docker compose -f compose.production.yml down --timeout 30 || true

            # ── Start new deployment ──────────────────────────────
            echo "Starting new deployment..."
            docker compose -f compose.production.yml up -d

            # ── Wait for services to be healthy ───────────────────
            # Java services take 60-90s to start (JVM + Spring context)
            # Node.js services take ~5-10s
            # Adjust sleep time based on your slowest service
            echo "Waiting for services to start (60s)..."
            sleep 60

            # ── Health check with retries ─────────────────────────
            MAX_RETRIES=30     # 30 retries × 5s = 150s maximum wait
            RETRY_COUNT=0
            until curl -sf http://localhost/health >/dev/null 2>&1; do
              RETRY_COUNT=$((RETRY_COUNT + 1))
              if [ $RETRY_COUNT -ge $MAX_RETRIES ]; then
                echo "ERROR: Health check failed after $MAX_RETRIES attempts"
                echo "Container status:"
                docker compose -f compose.production.yml ps
                echo "Recent logs:"
                docker compose -f compose.production.yml logs --tail=50
                exit 1
              fi
              echo "Health check attempt $RETRY_COUNT/$MAX_RETRIES — retrying in 5s..."
              sleep 5
            done

            echo "=== Deployment successful! ==="
            echo "Tag: $DEPLOY_TAG"
            docker compose -f compose.production.yml ps

  # ─────────────────────────────────────────────────────────────────
  # Job 5: Cleanup old images (runs after successful deploy)
  # Prevents disk from filling up with old image layers
  # ─────────────────────────────────────────────────────────────────
  cleanup:
    needs: deploy
    if: success()
    runs-on: ubuntu-latest
    steps:
      - name: Clean up old images on VM
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.VM_HOST }}
          username: ${{ secrets.VM_SSH_USER }}
          key: ${{ secrets.VM_SSH_KEY }}
          script: |
            # Remove layers that no longer have a tag pointing to them
            docker image prune -f

            # Keep only last 3 versions of each service image
            # Adjust the list to match your service names
            for img in my-app-backend my-app-frontend; do
              docker images "us-central1-docker.pkg.dev/MY_PROJECT_ID/MY_REPO/$img" \
                --format "{{.ID}} {{.Tag}}" | \
                grep -v latest | \
                tail -n +4 | \
                awk '{print $1}' | \
                xargs -r docker rmi || true
            done

            echo "Disk after cleanup:"
            df -h /

  # ─────────────────────────────────────────────────────────────────
  # Job 6: Notify on failure
  # ─────────────────────────────────────────────────────────────────
  notify-failure:
    needs: deploy
    if: failure()
    runs-on: ubuntu-latest
    steps:
      - name: Log failure info on VM
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.VM_HOST }}
          username: ${{ secrets.VM_SSH_USER }}
          key: ${{ secrets.VM_SSH_KEY }}
          script: |
            echo "DEPLOY FAILED at $(date)"
            echo "Check GitHub Actions: https://github.com/YOUR_ORG/YOUR_REPO/actions"
            echo "To rollback: gh workflow run deploy.yml -f rollback=true"
            # Add Slack/Discord/Telegram notification here if desired
```

---

## Part E — Multi-Domain Routing (Multiple Apps, One VM)

A single GCP VM can serve multiple completely different apps on different domains. This saves cost. The key is:

1. **Caddy handles all ports 80 and 443** — it's the single entry point
2. **Each domain gets its own `Caddyfile` block**
3. **Each app can be either:** a container on the `web` network, or a process bound to a host port

```caddy
# Caddyfile.production — multiple apps

# App 1: ERP system
erp.mycompany.com {
    handle /api/* {
        reverse_proxy backend:8081   # container on 'web' network
    }
    handle {
        reverse_proxy frontend:80    # container on 'web' network
    }
}

# App 2: Company portfolio (running as separate Docker stack on port 8090)
mycompany.com {
    reverse_proxy host.docker.internal:8090  # reaches host's port 8090
}
www.mycompany.com {
    redir https://mycompany.com{uri} permanent
}

# App 3: Internal tool (basic auth protection)
internal.mycompany.com {
    basicauth {
        # Generate hash with: caddy hash-password --plaintext yourpassword
        admin $2a$14$...hashedpassword...
    }
    reverse_proxy host.docker.internal:8091
}
```

**To add a second Docker stack (separate app) on the same VM:**

```bash
# On the VM, the second app connects to the same 'web' Docker network
# In the second app's docker-compose (NOT compose.production.yml):
# networks:
#   web:
#     external: true   ← connect to the shared network

# OR: bind the second app to a host port and reach it via host.docker.internal
# In second app's compose:
# ports:
#   - "8090:80"   ← bind host port 8090 to container port 80
```

---

## Part F — Secrets Management

**Rule**: Never put secrets in Git. Never put secrets in Dockerfiles. Always fetch at deploy time.

### How secrets flow in this architecture:

```
GCP Secret Manager
        │
        │  gcloud secrets versions access latest --secret="env"
        │  (runs on VM during deploy, using VM's service account)
        ▼
/home/user/apps/my-app/.env   (chmod 600 — only owner can read)
        │
        │  env_file: [.env]   (in compose.production.yml)
        ▼
Container environment variables at runtime
```

### Creating and updating secrets:

```bash
# Create a new secret
echo "MY_VALUE=abc123" | gcloud secrets create my-secret --data-file=-

# Create from file
gcloud secrets create app-env --data-file=.env.production

# Update an existing secret (adds a new version; old versions are kept)
gcloud secrets versions add app-env --data-file=.env.production.new

# View current value (careful — plaintext!)
gcloud secrets versions access latest --secret="app-env"

# List all secrets
gcloud secrets list

# Delete a secret
gcloud secrets delete my-secret
```

### What to put in secrets vs. build args:

| Type | Where to store | Example |
|---|---|---|
| API keys, passwords, tokens | GCP Secret Manager → `.env` | `DATABASE_URL`, `JWT_SECRET` |
| Service account JSON files | GCP Secret Manager (separate secret) | `firebase-sa.json` |
| Frontend env vars (public) | GitHub Actions secrets → Docker build args | `VITE_API_URL=/api` |
| Config (non-sensitive) | Directly in `compose.production.yml` `environment:` | `LOG_LEVEL=INFO` |

---

## Part G — Rollback Procedure

### Automatic rollback (via GitHub Actions UI):

```
1. Go to GitHub → Actions → "Deploy My App"
2. Click "Run workflow"
3. Check "Rollback to previous deployment" checkbox
4. Click "Run workflow"
```

This uses the `backups/previous-tag` file that the deploy script saves on each successful deploy.

### Manual rollback (SSH to VM):

```bash
# SSH into VM
gcloud compute ssh my-app-vm --zone=us-central1-c

cd /home/$USER/apps/my-app

# See what's currently deployed
cat .current-tag

# See the previous version
cat backups/previous-tag

# Roll back manually
PREV_TAG=$(cat backups/previous-tag)
echo "Rolling back to: $PREV_TAG"
export TAG="$PREV_TAG"
docker compose -f compose.production.yml pull
docker compose -f compose.production.yml up -d

# Verify health
curl -i http://localhost/health
```

### Deploy a specific version:

```
1. Find the commit SHA from GitHub → Commits
2. Go to GitHub → Actions → "Deploy My App"
3. Click "Run workflow"
4. Paste the SHA in "Image tag to deploy"
5. Run
```

---

## Part H — Monitoring and Logs

### View container logs:

```bash
# All containers combined (follow mode)
docker compose -f compose.production.yml logs -f

# Specific service
docker compose -f compose.production.yml logs -f backend

# Last 100 lines
docker compose -f compose.production.yml logs --tail=100 backend

# Caddy access logs (all HTTP requests)
docker exec caddy cat /var/log/caddy/access.log | tail -50

# Or stream them
docker exec caddy tail -f /var/log/caddy/access.log
```

### Check container health:

```bash
# See all containers and their health status
docker compose -f compose.production.yml ps

# Detailed health check info for a container
docker inspect my-app-backend | jq '.[0].State.Health'

# Health check from outside
curl -i http://localhost/health           # Caddy health
curl -i http://localhost:8081/health     # Backend health (internal only — port not exposed)
```

### Check resource usage:

```bash
# Live CPU and memory for all containers
docker stats

# Non-streaming snapshot
docker stats --no-stream

# VM-level resource usage
free -h        # memory
df -h          # disk
top            # CPU (press q to exit)
```

### Common log patterns to watch:

```bash
# See errors only from all containers
docker compose -f compose.production.yml logs --no-log-prefix 2>/dev/null | grep -i error

# Watch Caddy for failed upstream connections (backend not responding)
docker logs caddy 2>&1 | grep -i "dial\|refused\|upstream"

# Java service startup — confirm Spring loaded
docker logs my-app-backend 2>&1 | grep -i "started\|context\|port"
```

---

## Part I — Troubleshooting Runbook

### Quick diagnosis checklist:

```bash
# 1. Is the VM reachable?
ping YOUR_VM_IP

# 2. Is Caddy running?
curl -i http://YOUR_VM_IP/health   # from your laptop
# SSH into VM, then:
curl -i http://localhost/health

# 3. Are all containers up?
docker compose -f compose.production.yml ps
# Look for: Up (healthy) — not "Up" or "Exiting"

# 4. Can Caddy reach the backend?
docker logs caddy 2>&1 | tail -20

# 5. Is the backend itself alive?
docker logs my-app-backend 2>&1 | tail -30

# 6. Is there a disk space issue?
df -h /
# If disk >90% full: docker system prune -f
```

---

### Problem: Site not loading (502 Bad Gateway)

**Cause**: Caddy is up but can't reach the backend container.

```bash
# Check backend is running
docker compose -f compose.production.yml ps

# Check backend logs for startup errors
docker compose -f compose.production.yml logs backend

# Is the backend on the correct network?
docker inspect my-app-backend | jq '.[0].NetworkSettings.Networks'
# Should show "web" network

# Restart just the backend
docker compose -f compose.production.yml restart backend

# Wait for it to be healthy
docker compose -f compose.production.yml ps
```

---

### Problem: HTTPS not working / "Connection not secure"

**Cause**: Caddy couldn't get a Let's Encrypt certificate.

```bash
# Check Caddy logs for certificate errors
docker logs caddy 2>&1 | grep -i "certificate\|tls\|acme\|lets"

# Check that your domain resolves to this VM's IP
# Run from your laptop:
nslookup yourdomain.com
# Should return YOUR_VM_IP

# Check that port 80 and 443 are open
# From your laptop:
curl -I http://YOUR_VM_IP       # should get some response
curl -I https://YOUR_VM_IP      # may fail if cert not yet issued

# If Caddy has the wrong domain in Caddyfile.production:
# Fix the Caddyfile and reload Caddy without restarting:
docker exec caddy caddy reload --config /etc/caddy/Caddyfile
```

> **Important**: Let's Encrypt has rate limits. You can get at most 5 certificates per domain per week. If Caddy keeps failing to get a cert, it will exhaust your quota. Fix the underlying issue (DNS pointing to the right IP, ports 80/443 open) before retrying.

---

### Problem: "No such host" when pulling Docker images

```bash
# Re-authenticate Docker to Artifact Registry on the VM
gcloud auth configure-docker us-central1-docker.pkg.dev --quiet

# Verify you can pull
docker pull us-central1-docker.pkg.dev/MY_PROJECT_ID/MY_REPO/my-app-backend:latest
```

---

### Problem: "Permission denied" running Docker

```bash
# User not in docker group
sudo usermod -aG docker $USER
newgrp docker   # apply without logging out
```

---

### Problem: Out of disk space

```bash
# Check disk
df -h /

# Clean up stopped containers, unused images, build cache
docker system prune -f

# More aggressive — also removes unused volumes (CAREFUL with databases)
docker system prune --volumes -f

# Check what's taking space
docker system df

# Remove old images specifically
docker images | grep "3 weeks ago" | awk '{print $3}' | xargs docker rmi
```

---

### Problem: Java service keeps crashing (OOMKilled)

**Cause**: Memory limit in compose.production.yml is too low for the JVM.

```yaml
# In compose.production.yml, increase the memory limit:
deploy:
  resources:
    limits:
      memory: 1024M   # was 768M — increase until OOM stops
```

The JVM flags `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0` tell the JVM to respect the container's memory limit. With 768M limit, the heap will be ~576M (75%). If that's not enough, increase the limit.

---

### Problem: deploy.yml fails at SSH step

```bash
# Test SSH manually from your laptop
ssh -i ~/.ssh/github_actions_deploy YOUR_VM_IP "echo 'SSH works'"

# Check that the public key is in authorized_keys on the VM
cat ~/.ssh/authorized_keys

# Check VM firewall allows SSH (port 22)
gcloud compute firewall-rules list | grep ssh
```

---

### Full reset (nuclear option — use only if all else fails):

```bash
# SSH into VM
cd /home/$USER/apps/my-app

# Stop everything
docker compose -f compose.production.yml down

# Remove all containers, networks, cached images
docker system prune -af

# Recreate infrastructure
docker network create web
docker volume create caddy_data

# Re-deploy
export TAG=latest
docker compose -f compose.production.yml pull
docker compose -f compose.production.yml up -d
```

---

## Part J — Adapting This Guide to Different Stacks

### For a Django + React app:

**Backend Dockerfile:**
```dockerfile
FROM python:3.12-slim AS build
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
RUN python manage.py collectstatic --noinput

FROM python:3.12-slim
WORKDIR /app
COPY --from=build /app .
EXPOSE 8000
CMD ["gunicorn", "myproject.wsgi:application", "--bind", "0.0.0.0:8000", "--workers", "3"]
```

**Health check endpoint** (Django):
```python
# urls.py
path('health/', lambda request: HttpResponse('ok'), name='health'),
```

### For a Next.js app:

Next.js can serve its own API routes, so you may not need a separate backend container.

```dockerfile
FROM node:20-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

FROM node:20-alpine
RUN apk add --no-cache curl
WORKDIR /app
COPY --from=build /app/.next ./.next
COPY --from=build /app/public ./public
COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
EXPOSE 3000
CMD ["npm", "start"]
```

**Caddyfile** for Next.js (no separate API container):
```caddy
yourdomain.com {
    reverse_proxy frontend:3000
}
```

### For a Go app:

Go produces a single static binary — the final image can be `FROM scratch` (empty):

```dockerfile
FROM golang:1.22-alpine AS build
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o server ./cmd/server

FROM scratch       # literally empty — just the binary
COPY --from=build /app/server /server
EXPOSE 8080
ENTRYPOINT ["/server"]
```

---

## Quick Reference Card

### Daily Operations

```bash
# Check status
docker compose -f compose.production.yml ps

# View logs
docker compose -f compose.production.yml logs -f [service-name]

# Restart a specific service (no downtime for other services)
docker compose -f compose.production.yml restart backend

# Deploy a specific tag manually
TAG=abc123def export TAG && docker compose -f compose.production.yml pull && docker compose -f compose.production.yml up -d

# Reload Caddy config without restart (no downtime)
docker exec caddy caddy reload --config /etc/caddy/Caddyfile

# Update a secret
gcloud secrets versions add env --data-file=.env.new --project=MY_PROJECT_ID
# Then redeploy to pick up new values
```

### File Locations Summary

| File | Location in Repo | Location on VM |
|---|---|---|
| `compose.production.yml` | Repo root | `/home/user/apps/my-app/compose.production.yml` |
| `Caddyfile.production` | Repo root | `/home/user/apps/my-app/Caddyfile.production` |
| `.env` | NOT in repo — in Secret Manager | `/home/user/apps/my-app/.env` |
| `secrets/` | NOT in repo | `/home/user/apps/my-app/secrets/` |
| Backend Dockerfile | `backend-service/Dockerfile` | Built in GitHub Actions, not on VM |
| Frontend Dockerfile | `frontend/Dockerfile` | Built in GitHub Actions, not on VM |

### GitHub Secrets Summary

| Secret | Required | Value |
|---|---|---|
| `GCP_SA_KEY` | Yes | Service account JSON (from A7) |
| `VM_HOST` | Yes | VM's static IP address |
| `VM_SSH_USER` | Yes | Linux username on VM |
| `VM_SSH_KEY` | Yes | SSH private key |
| `VITE_API_URL` | For React/Vite | `/api` |

### Checklist for First Deploy

- [ ] GCP project created and billing enabled
- [ ] APIs enabled (compute, artifactregistry, secretmanager)
- [ ] VM created with `--scopes=cloud-platform`
- [ ] Static IP reserved and assigned to VM
- [ ] Firewall rules: tcp:80 and tcp:443 open
- [ ] Artifact Registry repository created
- [ ] GitHub Actions service account created with roles
- [ ] App's `.env` uploaded to Secret Manager as `env`
- [ ] Any credentials JSON uploaded to Secret Manager
- [ ] VM bootstrap script run (Docker installed, `web` network created, `caddy_data` volume created)
- [ ] DNS A record pointing `yourdomain.com` to VM's static IP
- [ ] All GitHub repository secrets configured
- [ ] `compose.production.yml` has correct image names and project ID
- [ ] `Caddyfile.production` has correct domain name
- [ ] Push to master and watch GitHub Actions pass

---

*This guide reflects the deployment architecture of Tasty ERP — a Java Spring Boot microservices app with a React/Vite frontend, deployed on GCP. The patterns generalize to any stack.*
