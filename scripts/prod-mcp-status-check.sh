#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"
SERVICE_CONTAINER="${SERVICE_CONTAINER:-chatbot-service}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-chatbot-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-chatbot-redis}"
MYSQL_DATABASE="${MYSQL_DATABASE:-chatbot}"

TEST_USER_PREFIX="${TEST_USER_PREFIX:-codex_mcp}"
TEST_USER="${TEST_USER:-${TEST_USER_PREFIX}_$(date +%s)_$$}"
TEST_EMAIL="${TEST_EMAIL:-${TEST_USER}@example.invalid}"
TEST_PASS="${TEST_PASS:-CodexTemp!2026}"
TEST_PASS_HASH="${TEST_PASS_HASH:-\$2a\$10\$841HC1ZzNYt/AqM.tj3ReO3/2h.vf.X9/V2s/ztT./.fUdh5Rz0by}"

TOKEN=""
REFRESH_TOKEN=""
CREATED_USER_ID=""
failures=0

log() {
  printf '[mcp] %s\n' "$*"
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

json_get() {
  local field="$1"
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], "") or "")' "$field"
}

env_value() {
  docker exec "$SERVICE_CONTAINER" printenv "$1" 2>/dev/null || true
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
    return
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
  rm -f /tmp/prod-mcp-login.json /tmp/prod-mcp-tools.json /tmp/prod-mcp-noauth.json
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
VALUES ('${escaped_user}','${escaped_email}','${escaped_hash}','Codex MCP User','USER',1,NOW(),NOW());" >/dev/null

  user_id="$(mysql_exec "SELECT id FROM user_account WHERE username='${escaped_user}' LIMIT 1;" | tr -d '[:space:]')"
  if [ -z "$user_id" ]; then
    fail "failed to create MCP test user"
    return
  fi
  CREATED_USER_ID="$user_id"
  log "created temporary user id=$CREATED_USER_ID"
}

login_and_capture_token() {
  local status login_body
  status="$(http_json_status /tmp/prod-mcp-login.json -X POST "${BASE_URL}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${TEST_USER}\",\"password\":\"${TEST_PASS}\"}")"
  login_body="$(cat /tmp/prod-mcp-login.json)"
  if [ "$status" != "200" ]; then
    fail "login returned $status"
    return
  fi

  TOKEN="$(printf '%s' "$login_body" | json_get token)"
  REFRESH_TOKEN="$(printf '%s' "$login_body" | json_get refreshToken)"
  if [ -z "$TOKEN" ]; then
    fail "login response did not include token"
    return
  fi
  pass "temporary user login works"
}

check_runtime_config() {
  local enabled server_enabled
  enabled="$(env_value APP_MCP_ENABLED)"
  server_enabled="$(env_value APP_MCP_SERVER_ENABLED)"
  enabled="${enabled:-false}"
  server_enabled="${server_enabled:-false}"
  log "enabled=${enabled}"
  log "server_enabled=${server_enabled}"
}

check_noauth() {
  local status
  status="$(http_json_status /tmp/prod-mcp-noauth.json "${BASE_URL}/api/mcp/tools")"
  if [ "$status" = "401" ]; then
    pass "/api/mcp/tools without token returns 401"
  else
    fail "/api/mcp/tools without token returned $status"
  fi
}

check_authenticated_state() {
  local status enabled server_enabled expected
  enabled="$(env_value APP_MCP_ENABLED)"
  server_enabled="$(env_value APP_MCP_SERVER_ENABLED)"
  enabled="${enabled:-false}"
  server_enabled="${server_enabled:-false}"
  expected="404"
  if [ "$enabled" = "true" ] && [ "$server_enabled" = "true" ]; then
    expected="200"
  fi

  status="$(http_json_status /tmp/prod-mcp-tools.json -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/mcp/tools")"
  if [ "$status" = "$expected" ]; then
    pass "/api/mcp/tools with token returns expected $expected"
  else
    fail "/api/mcp/tools with token returned $status, expected $expected"
  fi
}

main() {
  require_cmd docker
  require_cmd curl
  require_cmd python3

  check_runtime_config
  check_noauth
  create_test_user
  login_and_capture_token
  check_authenticated_state

  if [ "$failures" -gt 0 ]; then
    log "MCP status check failed: $failures issue(s)"
    exit 1
  fi
  log "MCP status check passed"
}

main "$@"
