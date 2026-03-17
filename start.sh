#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

echo "==> Stopping any running frontend..."
pkill -f "vite.*3000" 2>/dev/null && echo "    frontend stopped." || echo "    no frontend running."

echo "==> Pulling latest code..."
git pull origin main

echo "==> Stopping backend services..."
docker-compose down

echo "==> Starting backend services (building if changed)..."
docker-compose up -d --build

echo "==> Waiting for backend to be healthy..."
until curl -sf http://localhost:8080/actuator/health > /dev/null 2>&1; do
  echo "    backend not ready yet, waiting..."
  sleep 5
done
echo "    backend is healthy."

echo "==> Starting frontend..."
echo "    App will be available at http://$(curl -sf http://checkip.amazonaws.com 2>/dev/null || echo '<VM-IP>'):3000"
cd frontend && npm run dev -- --host 0.0.0.0 --port 3000
