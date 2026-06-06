#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"
SERVICE_CONTAINER="${SERVICE_CONTAINER:-chatbot-service}"
PGVECTOR_CONTAINER="${PGVECTOR_CONTAINER:-chatbot-pgvector}"

failures=0

log() {
  printf '[rag] %s\n' "$*"
}

pass() {
  printf '[pass] %s\n' "$*"
}

fail() {
  printf '[fail] %s\n' "$*" >&2
  failures=$((failures + 1))
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "missing command: $1"
  fi
}

env_value() {
  docker exec "$SERVICE_CONTAINER" printenv "$1" 2>/dev/null || true
}

http_status() {
  local path="$1"
  curl -sS -o /tmp/prod-rag-status-body.txt -w '%{http_code}' "${BASE_URL}${path}" || true
}

check_runtime_config() {
  local mode vector_enabled vector_url embedding_base embedding_provider embedding_model
  mode="$(env_value APP_RAG_MODE)"
  vector_enabled="$(env_value APP_RAG_VECTOR_ENABLED)"
  vector_url="$(env_value APP_RAG_VECTOR_JDBC_URL)"
  embedding_base="$(env_value APP_RAG_EMBEDDING_BASE_URL)"
  embedding_provider="$(env_value APP_RAG_EMBEDDING_PROVIDER)"
  embedding_model="$(env_value APP_RAG_EMBEDDING_MODEL)"

  mode="${mode:-keyword}"
  vector_enabled="${vector_enabled:-false}"

  log "mode=${mode}"
  log "vector_enabled=${vector_enabled}"
  log "embedding_provider=${embedding_provider:-unset}"
  log "embedding_model=${embedding_model:-unset}"

  if [ "$mode" = "keyword" ]; then
    pass "RAG mode is keyword"
  elif [ "$mode" = "vector" ] || [ "$mode" = "hybrid" ]; then
    pass "RAG mode is $mode"
  else
    fail "unknown APP_RAG_MODE: $mode"
  fi

  if [ "$vector_enabled" = "true" ]; then
    if [ -n "$vector_url" ]; then
      pass "vector JDBC URL is configured"
    else
      fail "APP_RAG_VECTOR_ENABLED=true but APP_RAG_VECTOR_JDBC_URL is empty"
    fi
    if [ -n "$embedding_base" ]; then
      pass "embedding base URL is configured"
    else
      fail "APP_RAG_VECTOR_ENABLED=true but APP_RAG_EMBEDDING_BASE_URL is empty"
    fi
  else
    pass "vector RAG is disabled"
  fi

  if [ "$mode" = "keyword" ] && [ "$vector_enabled" = "true" ]; then
    fail "keyword mode should not enable vector indexing in production"
  fi
}

check_pgvector_profile() {
  local pg_state
  pg_state="$(docker inspect -f '{{.State.Status}}' "$PGVECTOR_CONTAINER" 2>/dev/null || true)"
  if [ -z "$pg_state" ]; then
    pass "PGVector container is not created; vector profile is not active"
    return
  fi
  if [ "$pg_state" = "running" ]; then
    pass "PGVector container is running"
  else
    fail "PGVector container exists but is not running (state=$pg_state)"
  fi
}

check_keyword_rag_smoke() {
  local health
  health="$(http_status /api/chat/health)"
  if [ "$health" = "200" ]; then
    pass "chat health endpoint is available"
  else
    fail "/api/chat/health returned $health"
  fi
}

main() {
  require_cmd docker
  require_cmd curl

  check_runtime_config
  check_pgvector_profile
  check_keyword_rag_smoke

  if [ "$failures" -gt 0 ]; then
    log "RAG status check failed: $failures issue(s)"
    exit 1
  fi
  log "RAG status check passed"
}

main "$@"
