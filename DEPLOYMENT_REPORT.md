# Tasty ERP Deployment Report

This document explains how the deployment was started, what issues appeared, why they happened, and how each was solved. It includes allegorical explanations and visualizations, plus a guide to avoid the same issues next time.

## How Deployment Started

We aimed to deploy Tasty ERP to a new GCP VM using GitHub Actions. The pipeline builds Docker images, pushes them to Artifact Registry, and then remotely deploys on the VM using Docker Compose and Caddy (HTTP only).

High-level flow:

```
GitHub Actions
   |
   |-- build images (frontend, payment, waybill, config)
   |-- push to Artifact Registry
   |
   |-- SSH to VM
       |-- fetch secrets from Secret Manager
       |-- docker compose pull
       |-- docker compose up -d
       |-- health checks
```

## Issues Encountered, Meaning, and Fixes

### Issue 1: SSH authentication failed
**Symptoms**
- `ssh: no key found`
- `handshake failed: unable to authenticate`

**What it meant**
GitHub Actions could not authenticate to the VM because the SSH private key did not match the authorized key on the VM.

**Why it happened**
The VM key and GitHub secret were out of sync. Some attempts also mixed Windows paths and Cloud Shell context.

**Fix**
- Generated a new key on Windows.
- Placed the public key into `~/.ssh/authorized_keys` on the VM.
- Added the private key to GitHub Actions secret `VM_SSH_KEY`.
- Verified SSH with `ssh -i`.

**Allegory**
Think of the VM as a locked building and the GitHub Actions runner as a courier. The courier had a key from a different lock. We cut a new key and rekeyed the building so both matched.

**Visualization**
```
Before:
  VM lock  <-- key A (VM) 
  GHA key  <-- key B (GitHub)   -> no entry

After:
  VM lock  <-- key C (public)
  GHA key  <-- key C (private)  -> entry works
```

---

### Issue 2: `compose` files failed to upload to `/opt/...`
**Symptoms**
- `drone-scp error: Process exited with status 1`
- `create folder /opt/apps/tasty-erp`

**What it meant**
The SSH user could not create directories under `/opt` without sudo.

**Why it happened**
The deploy directory required root privileges.

**Fix**
Changed `DEPLOY_DIR` to a home-based path:
`/home/borissolomonia/apps/tasty-erp`

**Allegory**
Trying to park in a gated garage without a key. We instead parked in our driveway.

**Visualization**
```
/opt/apps      (locked)
    ^
    | no sudo

/home/borissolomonia/apps   (open)
```

---

### Issue 3: Secret Manager access denied on VM
**Symptoms**
- `ACCESS_TOKEN_SCOPE_INSUFFICIENT`

**What it meant**
The VM’s OAuth scopes did not allow Secret Manager access.

**Why it happened**
The VM was created without `cloud-platform` scope.

**Fix**
Stopped VM, updated service account scopes to `cloud-platform`, and restarted.

**Allegory**
The VM was like a worker with a badge that opened only some doors. We upgraded the badge so it could reach the vault.

**Visualization**
```
Old badge: [Compute, Logs] -> vault locked
New badge: [Cloud Platform] -> vault unlocked
```

---

### Issue 4: Docker not installed on VM
**Symptoms**
- `docker: command not found`

**What it meant**
The deploy script expected Docker, but the VM didn’t have it.

**Why it happened**
Fresh VM with no Docker packages installed.

**Fix**
Installed Docker CE and Docker Compose plugin manually.

**Allegory**
We delivered cargo to a warehouse without forklifts. Installing Docker was like bringing forklifts before unloading.

**Visualization**
```
Deploy wants: docker compose up
VM has:      nothing
Solution:    install Docker + compose
```

---

### Issue 5: Firebase credential file permission denied
**Symptoms**
- `FileNotFoundException: /secrets/firebase-sa.json (Permission denied)`

**What it meant**
Containers could not read the mounted Firebase key.

**Why it happened**
The file was created with mode `600`, which is not readable by the container user.

**Fix**
Set `secrets/firebase-sa.json` to `644` during deploy.

**Allegory**
We put the key in a safe and locked it to the owner only, but the services needed to read it. We changed it to read-only for all.

**Visualization**
```
600 -> owner only -> container user blocked
644 -> owner read/write, others read -> container ok
```

---

### Issue 6: Caddy not running, port 80 refused
**Symptoms**
- `curl http://localhost` -> connection refused
- No `caddy` container running

**What it meant**
Reverse proxy was not started, so nothing listened on port 80.

**Why it happened**
`depends_on` with health checks caused caddy not to start while services were still initializing.

**Fix**
Started Caddy explicitly with `--no-deps`, verified it served the frontend.

**Allegory**
The receptionist (Caddy) stayed home because the offices were still opening. We called them in manually so the front door opened.

**Visualization**
```
Browser -> :80 -> (no listener) -> refused

Browser -> :80 -> Caddy -> frontend/services -> OK
```

---

### Issue 7: External IP changed after VM stop/start
**Symptoms**
- SSH host key mismatch
- External IP changed

**What it meant**
Stopping/starting a VM with an ephemeral IP gives a new external IP.

**Why it happened**
External IP was not reserved as a static address.

**Fix**
Updated `VM_HOST` secret and known_hosts after each change.

**Allegory**
We moved the office to a new address but kept sending couriers to the old address. Updating the address fixed it.

**Visualization**
```
Old IP -> not reachable
New IP -> reachable
```

---

## Root Cause Summary (One Page)

```
SSH key mismatch -> new key + authorized_keys
Permission (dir) -> use /home path
VM scopes        -> cloud-platform
Docker missing   -> install Docker
Secret perms     -> chmod 644
Caddy not up     -> start caddy --no-deps
IP changes       -> update VM_HOST / reserve IP
Firewall         -> allow tcp:80
```

## Deployment Guide (Next Time, No Surprises)

### 1) VM Creation Checklist
- Region/zone: us-central1-c
- Service account: 282950310544-compute@developer.gserviceaccount.com
- OAuth scope: `https://www.googleapis.com/auth/cloud-platform`
- External IP: reserve static if you want it stable
- Open firewall: tcp:80 ingress

### 2) SSH Setup
On Windows:
```
ssh-keygen -t ed25519 -C "github-actions-deploy" -f "C:\\Users\\Admin\\.ssh\\tasty-erp-deploy"
```
On VM:
```
printf '%s\n' 'ssh-ed25519 AAAA... github-actions-deploy' > ~/.ssh/authorized_keys
chmod 700 ~/.ssh
chmod 600 ~/.ssh/authorized_keys
```
GitHub secrets:
- VM_HOST = external IP
- VM_SSH_USER = borissolomonia
- VM_SSH_KEY = private key contents

### 3) Install Docker on VM (Single-Line Safe Commands)
```
sudo apt-get update
sudo apt-get install -y ca-certificates curl gnupg
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg
sudo bash -c 'echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" > /etc/apt/sources.list.d/docker.list'
sudo apt-get update
sudo apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker borissolomonia
newgrp docker
docker version
```

### 4) Deploy Directory
```
mkdir -p /home/borissolomonia/apps/tasty-erp
```

### 5) Secrets & Permissions
Ensure Secret Manager access and fix file permissions in deploy:
```
chmod 600 .env
chmod 644 secrets/firebase-sa.json
```

### 6) Caddy Startup
If caddy does not start because dependencies are slow:
```
docker compose -f compose.production.yml up -d --no-deps caddy
```

### 7) Firewall
If external HTTP does not respond:
```
gcloud compute firewall-rules create allow-http --direction=INGRESS --priority=1000 --network=default --action=ALLOW --rules=tcp:80 --source-ranges=0.0.0.0/0
```

### 8) Verification
On VM:
```
docker compose -f compose.production.yml ps
curl -v http://localhost/
```
From outside:
```
curl -I http://<EXTERNAL_IP>
```

## Final Status
- Containers healthy
- Caddy serving frontend and proxying APIs
- External IP responds on port 80

If any future deployment fails, check:
1) SSH auth
2) VM scopes
3) Docker installed
4) Secrets permissions
5) Caddy running
6) Firewall rules
