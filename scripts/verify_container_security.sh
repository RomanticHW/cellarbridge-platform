#!/usr/bin/env bash
set -euo pipefail

backend_image="${BACKEND_IMAGE:-cellarbridge/backend:security-check}"
frontend_image="${FRONTEND_IMAGE:-cellarbridge/frontend:security-check}"

if [[ -z "${BACKEND_IMAGE:-}" ]]; then
  docker build --file backend/Dockerfile --tag "$backend_image" .
fi
if [[ -z "${FRONTEND_IMAGE:-}" ]]; then
  docker build --file frontend/Dockerfile --tag "$frontend_image" .
fi

for image in "$backend_image" "$frontend_image"; do
  configured_user="$(docker image inspect --format '{{.Config.User}}' "$image")"
  if [[ -z "$configured_user" || "$configured_user" == "0" || "$configured_user" == "root" ]]; then
    printf 'Runtime image %s does not declare a non-root user\n' "$image" >&2
    exit 1
  fi
  docker run --rm --entrypoint sh "$image" -c 'test "$(id -u)" -ne 0'
  printf 'Verified non-root runtime: %s (%s)\n' "$image" "$configured_user"
done
