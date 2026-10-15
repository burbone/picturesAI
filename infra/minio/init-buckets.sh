#!/usr/bin/env bash
set -euo pipefail

ALIAS="${MINIO_ALIAS:-local}"
ENDPOINT="${MINIO_ENDPOINT:-http://minio:9000}"

BUCKETS=(
  ai-gallery-originals
  ai-gallery-thumbnails
  ai-gallery-seed
)

mc alias set "$ALIAS" "$ENDPOINT" "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD"

for bucket in "${BUCKETS[@]}"; do
  mc mb --ignore-existing "$ALIAS/$bucket"
done