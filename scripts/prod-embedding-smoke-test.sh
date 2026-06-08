#!/usr/bin/env bash
set -euo pipefail

SERVICE_CONTAINER="${SERVICE_CONTAINER:-chatbot-service}"

log() { printf '[embedding] %s\n' "$*"; }
pass() { printf '[pass] %s\n' "$*"; }
fail() { printf '[fail] %s\n' "$*" >&2; exit 1; }

env_value() {
  docker exec "$SERVICE_CONTAINER" printenv "$1" 2>/dev/null || true
}

provider="$(env_value APP_RAG_EMBEDDING_PROVIDER)"
base_url="$(env_value APP_RAG_EMBEDDING_BASE_URL)"
path="$(env_value APP_RAG_EMBEDDING_PATH)"
model="$(env_value APP_RAG_EMBEDDING_MODEL)"
api_key="$(env_value APP_RAG_EMBEDDING_API_KEY)"
dimensions="$(env_value APP_RAG_VECTOR_DIMENSIONS)"
encoding_format="$(env_value APP_RAG_EMBEDDING_ENCODING_FORMAT)"

[ -n "$provider" ] || fail "APP_RAG_EMBEDDING_PROVIDER is empty"
[ -n "$base_url" ] || fail "APP_RAG_EMBEDDING_BASE_URL is empty"
[ -n "$model" ] || fail "APP_RAG_EMBEDDING_MODEL is empty"
[ -n "$dimensions" ] || fail "APP_RAG_VECTOR_DIMENSIONS is empty"

base_url="${base_url%/}"
if [ -z "$path" ]; then
  if [ "$provider" = "openai-compatible" ]; then
    path="/v1/embeddings"
  else
    path="/api/embeddings"
  fi
fi
case "$path" in
  /*) ;;
  *) path="/$path" ;;
esac

tmp_body="/tmp/prod-embedding-smoke-body.json"
tmp_response="/tmp/prod-embedding-smoke-response.json"

if [ "$provider" = "openai-compatible" ]; then
  python3 - "$model" "$dimensions" "${encoding_format:-float}" > "$tmp_body" <<'PY'
import json, sys
print(json.dumps({
    "model": sys.argv[1],
    "input": "AI Studio embedding smoke test",
    "dimensions": int(sys.argv[2]),
    "encoding_format": sys.argv[3],
}, ensure_ascii=False))
PY
else
  python3 - "$model" > "$tmp_body" <<'PY'
import json, sys
print(json.dumps({
    "model": sys.argv[1],
    "prompt": "AI Studio embedding smoke test",
}, ensure_ascii=False))
PY
fi

curl_args=(-sS -o "$tmp_response" -w "%{http_code}" -X POST "${base_url}${path}" -H "Content-Type: application/json" -d "@${tmp_body}")
if [ -n "$api_key" ]; then
  curl_args+=(-H "Authorization: Bearer ${api_key}")
fi

status="$(curl "${curl_args[@]}" || true)"
[ "$status" = "200" ] || {
  head -c 300 "$tmp_response" >&2 || true
  printf '\n' >&2
  fail "embedding API returned $status"
}

actual_dimensions="$(python3 - "$provider" "$tmp_response" <<'PY'
import json, sys
provider, path = sys.argv[1], sys.argv[2]
data = json.load(open(path, encoding="utf-8"))
if provider == "openai-compatible":
    vec = data.get("data", [{}])[0].get("embedding", [])
else:
    vec = data.get("embedding", [])
print(len(vec))
PY
)"

log "provider=$provider"
log "model=$model"
log "dimensions=$actual_dimensions"
[ "$actual_dimensions" = "$dimensions" ] || fail "embedding dimensions mismatch, expected $dimensions got $actual_dimensions"
pass "embedding API smoke test passed"
