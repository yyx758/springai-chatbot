# Production Verification Scripts

Run these scripts on the production host from the project directory, usually:

```bash
cd /opt/springai-chatbot
bash scripts/prod-health-check.sh
bash scripts/prod-e2e-verify.sh
```

## prod-health-check.sh

Checks deployment basics without changing application data:

- required containers are running
- `chatbot-service` and `chatbot-gateway` use the same `APP_JWT_SECRET`
- MySQL and Redis respond
- Gateway pages and public health endpoints return expected status codes
- `/api/auth/me` without a token returns `401`

## prod-e2e-verify.sh

Runs `prod-health-check.sh`, then performs a real login flow:

- creates one temporary test user in MySQL
- logs in through Gateway
- verifies `/api/auth/me`
- verifies `/api/chat/stats`
- verifies `/api/knowledge/documents`
- deletes the temporary user and refresh token on exit

The scripts do not print JWT secrets, database passwords, or access tokens.

Useful overrides:

```bash
BASE_URL=http://127.0.0.1:9000 bash scripts/prod-e2e-verify.sh
COMPOSE_FILE=docker-compose.prod.yml bash scripts/prod-health-check.sh
```
