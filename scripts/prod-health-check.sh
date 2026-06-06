#!/usr/bin/env bash
set -euo pipefail

COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"

SERVICE_CONTAINER="${SERVICE_CONTAINER:-chatbot-service}"
GATEWAY_CONTAINER="${GATEWAY_CONTAINER:-chatbot-gateway}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-chatbot-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-chatbot-redis}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-chatbot-kafka}"
NACOS_CONTAINER="${NACOS_CONTAINER:-chatbot-nacos}"

failures=0

log() {
  printf '[health] %s\n' "$*"
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

http_status() {
  local path="$1"
  curl -sS -o /tmp/prod-health-body.txt -w '%{http_code}' "${BASE_URL}${path}" || true
}

check_container_running() {
  local container="$1"
  local state
  state="$(docker inspect -f '{{.State.Status}}' "$container" 2>/dev/null || true)"
  if [ "$state" = "running" ]; then
    pass "$container is running"
  else
    fail "$container is not running (state=${state:-missing})"
  fi
}

check_compose_status() {
  if [ ! -f "$COMPOSE_FILE" ]; then
    fail "compose file not found: $COMPOSE_FILE"
    return
  fi
  if docker compose -f "$COMPOSE_FILE" ps >/tmp/prod-health-compose-ps.txt 2>/tmp/prod-health-compose-ps.err; then
    pass "docker compose ps is available"
  else
    fail "docker compose ps failed: $(head -c 200 /tmp/prod-health-compose-ps.err)"
  fi
}

check_jwt_secret_consistency() {
  local service_secret gateway_secret service_len gateway_len
  service_secret="$(docker exec "$SERVICE_CONTAINER" printenv APP_JWT_SECRET 2>/dev/null || true)"
  gateway_secret="$(docker exec "$GATEWAY_CONTAINER" printenv APP_JWT_SECRET 2>/dev/null || true)"
  service_len="${#service_secret}"
  gateway_len="${#gateway_secret}"

  if [ "$service_len" -lt 32 ]; then
    fail "chatbot-service APP_JWT_SECRET too short (len=$service_len)"
  else
    pass "chatbot-service APP_JWT_SECRET length ok (len=$service_len)"
  fi

  if [ "$gateway_len" -lt 32 ]; then
    fail "chatbot-gateway APP_JWT_SECRET too short (len=$gateway_len)"
  else
    pass "chatbot-gateway APP_JWT_SECRET length ok (len=$gateway_len)"
  fi

  if [ "$service_secret" = "$gateway_secret" ] && [ "$service_len" -ge 32 ]; then
    pass "JWT secret is consistent between service and gateway"
  else
    fail "JWT secret mismatch between service and gateway (service_len=$service_len gateway_len=$gateway_len)"
  fi
}

check_mysql() {
  local password
  password="$(docker exec "$MYSQL_CONTAINER" printenv MYSQL_ROOT_PASSWORD 2>/dev/null || true)"
  if [ -z "$password" ]; then
    fail "cannot read MYSQL_ROOT_PASSWORD from $MYSQL_CONTAINER"
    return
  fi
  if docker exec -e MYSQL_PWD="$password" "$MYSQL_CONTAINER" mysql -uroot -N chatbot -e 'select 1;' >/dev/null 2>&1; then
    pass "MySQL query works"
  else
    fail "MySQL query failed"
  fi
}

check_redis() {
  local password
  password="$(docker exec "$SERVICE_CONTAINER" printenv SPRING_DATA_REDIS_PASSWORD 2>/dev/null || true)"
  if [ -n "$password" ]; then
    if docker exec "$REDIS_CONTAINER" redis-cli -a "$password" ping 2>/dev/null | grep -q PONG; then
      pass "Redis ping works"
    else
      fail "Redis ping failed"
    fi
  else
    fail "cannot read Redis password from $SERVICE_CONTAINER"
  fi
}

check_http() {
  local login chat health me_no_token
  login="$(http_status /login)"
  chat="$(http_status /chat)"
  health="$(http_status /api/chat/health)"
  me_no_token="$(http_status /api/auth/me)"

  [ "$login" = "200" ] && pass "/login returns 200" || fail "/login returned $login"
  [ "$chat" = "200" ] && pass "/chat returns 200" || fail "/chat returned $chat"
  [ "$health" = "200" ] && pass "/api/chat/health returns 200" || fail "/api/chat/health returned $health"
  [ "$me_no_token" = "401" ] && pass "/api/auth/me without token returns 401" || fail "/api/auth/me without token returned $me_no_token"
}

main() {
  log "base url: $BASE_URL"
  require_cmd docker
  require_cmd curl

  check_compose_status
  check_container_running "$MYSQL_CONTAINER"
  check_container_running "$REDIS_CONTAINER"
  check_container_running "$KAFKA_CONTAINER"
  check_container_running "$NACOS_CONTAINER"
  check_container_running "$SERVICE_CONTAINER"
  check_container_running "$GATEWAY_CONTAINER"
  check_jwt_secret_consistency
  check_mysql
  check_redis
  check_http

  if [ "$failures" -gt 0 ]; then
    log "health check failed: $failures issue(s)"
    exit 1
  fi
  log "health check passed"
}

main "$@"
