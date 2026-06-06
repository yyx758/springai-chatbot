#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.prod.yml}"
BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"

SERVICE_CONTAINER="${SERVICE_CONTAINER:-chatbot-service}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-chatbot-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-chatbot-redis}"
MYSQL_DATABASE="${MYSQL_DATABASE:-chatbot}"

TEST_USER_PREFIX="${TEST_USER_PREFIX:-codex_e2e}"
TEST_USER="${TEST_USER:-${TEST_USER_PREFIX}_$(date +%s)_$$}"
TEST_EMAIL="${TEST_EMAIL:-${TEST_USER}@example.invalid}"
TEST_PASS="${TEST_PASS:-CodexTemp!2026}"

# BCrypt hash for TEST_PASS=CodexTemp!2026 generated with Spring Security BCryptPasswordEncoder.
TEST_PASS_HASH="${TEST_PASS_HASH:-\$2a\$10\$841HC1ZzNYt/AqM.tj3ReO3/2h.vf.X9/V2s/ztT./.fUdh5Rz0by}"

TOKEN=""
REFRESH_TOKEN=""
CREATED_USER_ID=""

log() {
  printf '[e2e] %s\n' "$*"
}

fail() {
  printf '[fail] %s\n' "$*" >&2
  exit 1
}

require_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    fail "missing command: $1"
  fi
}

json_get() {
  local field="$1"
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], "") or "")' "$field"
}

mysql_password() {
  docker exec "$MYSQL_CONTAINER" printenv MYSQL_ROOT_PASSWORD 2>/dev/null || true
}

mysql_exec() {
  local sql="$1"
  local password
  password="$(mysql_password)"
  if [ -z "$password" ]; then
    fail "cannot read MYSQL_ROOT_PASSWORD from $MYSQL_CONTAINER"
  fi
  printf '%s\n' "$sql" |
    docker exec -i -e MYSQL_PWD="$password" "$MYSQL_CONTAINER" mysql -uroot -N "$MYSQL_DATABASE"
}

redis_password() {
  docker exec "$SERVICE_CONTAINER" printenv SPRING_DATA_REDIS_PASSWORD 2>/dev/null || true
}

cleanup() {
  set +e
  if [ -n "$REFRESH_TOKEN" ]; then
    local redis_pass
    redis_pass="$(redis_password)"
    if [ -n "$redis_pass" ]; then
      docker exec "$REDIS_CONTAINER" redis-cli -a "$redis_pass" DEL "refresh_token:${REFRESH_TOKEN}" >/dev/null 2>&1
    fi
  fi
  if [ -n "$CREATED_USER_ID" ]; then
    mysql_exec "DELETE FROM user_account WHERE id=${CREATED_USER_ID};" >/dev/null 2>&1
  else
    mysql_exec "DELETE FROM user_account WHERE username='${TEST_USER}';" >/dev/null 2>&1
  fi
  rm -f /tmp/prod-e2e-login.json /tmp/prod-e2e-me.json /tmp/prod-e2e-stats.json /tmp/prod-e2e-knowledge.json
}
trap cleanup EXIT

http_json_status() {
  local output="$1"
  shift
  curl -sS -o "$output" -w '%{http_code}' "$@" || true
}

create_test_user() {
  local escaped_user escaped_email escaped_hash user_id
  escaped_user="${TEST_USER//\'/\'\'}"
  escaped_email="${TEST_EMAIL//\'/\'\'}"
  escaped_hash="${TEST_PASS_HASH//\'/\'\'}"

  mysql_exec "DELETE FROM user_account WHERE username='${escaped_user}' OR email='${escaped_email}';" >/dev/null
  mysql_exec "INSERT INTO user_account(username,email,password_hash,display_name,role,enabled,created_time,updated_time)
VALUES ('${escaped_user}','${escaped_email}','${escaped_hash}','Codex E2E User','USER',1,NOW(),NOW());" >/dev/null

  user_id="$(mysql_exec "SELECT id FROM user_account WHERE username='${escaped_user}' LIMIT 1;" | tr -d '[:space:]')"
  if [ -z "$user_id" ]; then
    fail "failed to create test user"
  fi
  CREATED_USER_ID="$user_id"
  log "created temporary user id=$CREATED_USER_ID"
}

login_and_capture_token() {
  local status login_body
  status="$(http_json_status /tmp/prod-e2e-login.json -X POST "${BASE_URL}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${TEST_USER}\",\"password\":\"${TEST_PASS}\"}")"
  login_body="$(cat /tmp/prod-e2e-login.json)"
  if [ "$status" != "200" ]; then
    printf '%s\n' "$login_body" >&2
    fail "login returned $status"
  fi

  TOKEN="$(printf '%s' "$login_body" | json_get token)"
  REFRESH_TOKEN="$(printf '%s' "$login_body" | json_get refreshToken)"
  if [ -z "$TOKEN" ]; then
    printf '%s\n' "$login_body" >&2
    fail "login response did not include token"
  fi
  log "login_status=ok"
}

check_authenticated_endpoint() {
  local name="$1"
  local path="$2"
  local output="$3"
  local status
  status="$(http_json_status "$output" -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}${path}")"
  if [ "$status" != "200" ]; then
    printf '%s\n' "$(head -c 300 "$output")" >&2
    fail "$name returned $status"
  fi
  log "$name=200"
}

verify_profile_user() {
  local user_id username
  user_id="$(json_get userId < /tmp/prod-e2e-me.json)"
  username="$(json_get username < /tmp/prod-e2e-me.json)"
  if [ "$user_id" != "$CREATED_USER_ID" ] || [ "$username" != "$TEST_USER" ]; then
    fail "profile mismatch: expected id=$CREATED_USER_ID username=$TEST_USER, got id=$user_id username=$username"
  fi
  log "profile matches temporary user"
}

main() {
  require_cmd docker
  require_cmd curl
  require_cmd python3

  if [ -f "${SCRIPT_DIR}/prod-health-check.sh" ]; then
    COMPOSE_FILE="$COMPOSE_FILE" BASE_URL="$BASE_URL" bash "${SCRIPT_DIR}/prod-health-check.sh"
  else
    log "skip health check: ${SCRIPT_DIR}/prod-health-check.sh not found"
  fi

  create_test_user
  login_and_capture_token
  check_authenticated_endpoint "/api/auth/me" "/api/auth/me" /tmp/prod-e2e-me.json
  verify_profile_user
  check_authenticated_endpoint "/api/chat/stats" "/api/chat/stats" /tmp/prod-e2e-stats.json
  check_authenticated_endpoint "/api/knowledge/documents" "/api/knowledge/documents?page=1&size=1" /tmp/prod-e2e-knowledge.json

  log "verify=passed"
}

main "$@"
