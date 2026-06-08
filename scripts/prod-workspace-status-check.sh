#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://127.0.0.1:9000}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-chatbot-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-chatbot-redis}"
SERVICE_CONTAINER="${SERVICE_CONTAINER:-chatbot-service}"
MYSQL_DATABASE="${MYSQL_DATABASE:-chatbot}"
TEST_USER="${TEST_USER:-cw_$(date +%s)_$$}"
TEST_EMAIL="${TEST_EMAIL:-${TEST_USER}@example.invalid}"
TEST_PASS="${TEST_PASS:-CodexTemp!2026}"
TEST_PASS_HASH="${TEST_PASS_HASH:-\$2a\$10\$841HC1ZzNYt/AqM.tj3ReO3/2h.vf.X9/V2s/ztT./.fUdh5Rz0by}"

TOKEN=""
REFRESH_TOKEN=""
CREATED_USER_ID=""
SESSION_ID=""

log() { printf '[workspace] %s\n' "$*"; }
pass() { printf '[pass] %s\n' "$*"; }
fail() { printf '[fail] %s\n' "$*" >&2; exit 1; }

json_get() {
  local field="$1"
  python3 -c 'import json,sys; print(json.load(sys.stdin).get(sys.argv[1], "") or "")' "$field"
}

mysql_password() {
  docker exec "$MYSQL_CONTAINER" printenv MYSQL_ROOT_PASSWORD 2>/dev/null || true
}

mysql_exec() {
  local sql="$1" password
  password="$(mysql_password)"
  [ -n "$password" ] || fail "cannot read MYSQL_ROOT_PASSWORD"
  printf '%s\n' "$sql" | docker exec -i -e MYSQL_PWD="$password" "$MYSQL_CONTAINER" mysql -uroot -N "$MYSQL_DATABASE"
}

redis_password() {
  docker exec "$SERVICE_CONTAINER" printenv SPRING_DATA_REDIS_PASSWORD 2>/dev/null || true
}

cleanup() {
  set +e
  if [ -n "$REFRESH_TOKEN" ]; then
    redis_pass="$(redis_password)"
    [ -n "$redis_pass" ] && docker exec "$REDIS_CONTAINER" redis-cli -a "$redis_pass" DEL "refresh_token:${REFRESH_TOKEN}" >/dev/null 2>&1
  fi
  if [ -n "$CREATED_USER_ID" ]; then
    mysql_exec "DELETE FROM knowledge_document WHERE user_id=${CREATED_USER_ID};" >/dev/null 2>&1
    mysql_exec "DELETE FROM agent_workspace_file WHERE user_id=${CREATED_USER_ID};" >/dev/null 2>&1
    mysql_exec "DELETE FROM agent_workspace WHERE user_id=${CREATED_USER_ID};" >/dev/null 2>&1
    mysql_exec "DELETE FROM file_record WHERE uploader_id=${CREATED_USER_ID};" >/dev/null 2>&1
    mysql_exec "DELETE FROM user_account WHERE id=${CREATED_USER_ID};" >/dev/null 2>&1
  fi
  rm -f /tmp/prod-workspace-*.json
}
trap cleanup EXIT

http_status() {
  local output="$1"
  shift
  curl -sS -o "$output" -w '%{http_code}' "$@" || true
}

create_user() {
  local escaped_user escaped_email escaped_hash
  escaped_user="${TEST_USER//\'/\'\'}"
  escaped_email="${TEST_EMAIL//\'/\'\'}"
  escaped_hash="${TEST_PASS_HASH//\'/\'\'}"

  mysql_exec "DELETE FROM user_account WHERE username='${escaped_user}' OR email='${escaped_email}';" >/dev/null
  mysql_exec "INSERT INTO user_account(username,email,password_hash,display_name,role,enabled,created_time,updated_time)
VALUES ('${escaped_user}','${escaped_email}','${escaped_hash}','Codex Workspace User','USER',1,NOW(),NOW());" >/dev/null
  CREATED_USER_ID="$(mysql_exec "SELECT id FROM user_account WHERE username='${escaped_user}' LIMIT 1;" | tr -d '[:space:]')"
  [ -n "$CREATED_USER_ID" ] || fail "failed to create temporary user"
  SESSION_ID="${CREATED_USER_ID}_workspace_check"
  log "created temporary user id=$CREATED_USER_ID"
}

login() {
  status="$(http_status /tmp/prod-workspace-login.json -X POST "${BASE_URL}/api/auth/login" \
    -H 'Content-Type: application/json' \
    -d "{\"username\":\"${TEST_USER}\",\"password\":\"${TEST_PASS}\"}")"
  if [ "$status" != "200" ]; then
    head -c 300 /tmp/prod-workspace-login.json >&2 || true
    printf '\n' >&2
    fail "login returned $status"
  fi
  TOKEN="$(json_get token < /tmp/prod-workspace-login.json)"
  REFRESH_TOKEN="$(json_get refreshToken < /tmp/prod-workspace-login.json)"
  [ -n "$TOKEN" ] || fail "login did not return token"
  pass "temporary user login works"
}

main() {
  command -v docker >/dev/null || fail "missing docker"
  command -v curl >/dev/null || fail "missing curl"
  command -v python3 >/dev/null || fail "missing python3"

  noauth="$(http_status /tmp/prod-workspace-noauth.json "${BASE_URL}/api/agent/workspaces/current?sessionId=0_test")"
  [ "$noauth" = "401" ] || fail "workspace current without token returned $noauth"
  pass "workspace API without token returns 401"

  create_user
  login

  status="$(http_status /tmp/prod-workspace-current.json -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/agent/workspaces/current?sessionId=${SESSION_ID}")"
  [ "$status" = "200" ] || fail "workspace current returned $status"
  WORKSPACE_ID="$(python3 -c 'import json; print(json.load(open("/tmp/prod-workspace-current.json"))["workspace"]["id"])')"
  [ -n "$WORKSPACE_ID" ] || fail "workspace id missing"
  pass "workspace current works"

  status="$(http_status /tmp/prod-workspace-create.json -X POST "${BASE_URL}/api/agent/workspaces/${WORKSPACE_ID}/files" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d '{"relativePath":"checks/hello.md","content":"# Hello Workspace","overwrite":false}')"
  [ "$status" = "200" ] || fail "workspace create file returned $status"
  pass "workspace file create works"

  status="$(http_status /tmp/prod-workspace-read.json -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/agent/workspaces/${WORKSPACE_ID}/files/content?path=checks%2Fhello.md")"
  [ "$status" = "200" ] || fail "workspace read file returned $status"
  grep -q "Hello Workspace" /tmp/prod-workspace-read.json || fail "workspace file content missing"
  pass "workspace file read works"

  status="$(http_status /tmp/prod-workspace-create-code.json -X POST "${BASE_URL}/api/agent/workspaces/${WORKSPACE_ID}/files" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d '{"relativePath":"src/main/java/com/example/App.java","content":"package com.example;\nclass App {}","overwrite":false}')"
  [ "$status" = "200" ] || fail "workspace create Java file returned $status"
  pass "workspace Java source file create works"

  status="$(http_status /tmp/prod-workspace-read-code.json -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/agent/workspaces/${WORKSPACE_ID}/files/content?path=src%2Fmain%2Fjava%2Fcom%2Fexample%2FApp.java")"
  [ "$status" = "200" ] || fail "workspace read Java file returned $status"
  grep -q "class App" /tmp/prod-workspace-read-code.json || fail "workspace Java file content missing"
  pass "workspace Java source file read works"

  status="$(http_status /tmp/prod-workspace-create-gitignore.json -X POST "${BASE_URL}/api/agent/workspaces/${WORKSPACE_ID}/files" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d '{"relativePath":".gitignore","content":"target/\n.idea/\n","overwrite":false}')"
  [ "$status" = "200" ] || fail "workspace create .gitignore returned $status"
  pass "workspace project dotfile create works"

  status="$(http_status /tmp/prod-workspace-create-target.json -X POST "${BASE_URL}/api/agent/workspaces/${WORKSPACE_ID}/files" \
    -H "Authorization: Bearer ${TOKEN}" -H 'Content-Type: application/json' \
    -d '{"relativePath":"target/classes/application.yml","content":"server:\n  port: 8080","overwrite":false}')"
  [ "$status" = "400" ] || fail "workspace target output path returned $status, expected 400"
  pass "workspace build output path is blocked"

  log "workspace status check passed"
}

main "$@"
