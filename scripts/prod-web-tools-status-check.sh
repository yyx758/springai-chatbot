#!/usr/bin/env bash
set -euo pipefail

SERVICE_CONTAINER="${SERVICE_CONTAINER:-chatbot-service}"

log() { printf '[web-tools] %s\n' "$*"; }
pass() { printf '[pass] %s\n' "$*"; }
fail() { printf '[fail] %s\n' "$*" >&2; exit 1; }

env_value() {
  docker exec "$SERVICE_CONTAINER" printenv "$1" 2>/dev/null || true
}

main() {
  command -v docker >/dev/null || fail "missing docker"
  enabled="$(env_value APP_WEB_TOOLS_ENABLED)"
  api_key="$(env_value APP_FIRECRAWL_API_KEY)"
  enabled="${enabled:-false}"
  log "enabled=${enabled}"
  if [ "$enabled" = "true" ] && [ -z "$api_key" ]; then
    fail "APP_WEB_TOOLS_ENABLED=true but APP_FIRECRAWL_API_KEY is empty"
  fi
  if [ "$enabled" = "true" ]; then
    pass "web tools are enabled and API key is configured"
  else
    pass "web tools are disabled by default"
  fi
  log "web tools status check passed"
}

main "$@"
