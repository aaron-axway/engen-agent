# Webhook Mock Server

Mock HTTP server that pretends to be **Axway Amplify Central API**, **Highmark OAuth**, and **Highmark ServiceNow Catalog API** so the webhook service can be exercised end-to-end without real upstream systems.

Captures every inbound request to stdout so you can visually confirm the webhook service is calling what you expect — in particular the `PUT {selfLink}/approval` triggered by a ServiceNow approval/rejection.

## Running

The script has zero external dependencies (Python stdlib only) and a PEP 723 inline script header, so `uv` handles everything — no manual venv, no install step.

### From the repo root

```bash
uv run test-scripts/mock-server/mock_server.py
```

### From inside this folder

```bash
cd test-scripts/mock-server
uv run mock_server.py
```

### Using the declared entry point

The `pyproject.toml` declares a `mock-server` script. After `uv sync` (creates a local `.venv`), you can run:

```bash
cd test-scripts/mock-server
uv sync
uv run mock-server
```

### Custom port

Defaults to `9090`. Override with a positional arg:

```bash
uv run test-scripts/mock-server/mock_server.py 9191
```

## What it pretends to be

| Method & Path | What it returns |
|---|---|
| `GET /apis/.../assetrequests/...` | Fake AssetRequest JSON with `metadata.selfLink` and `owner.id` |
| `GET /team/...` | `{"result": {"id": "mock-team", "name": "mock-team", "users": []}}` |
| `GET /user/...` | `{"result": {"guid": "mock-user", "email": "mock@example.com"}}` |
| `PUT /.../approval` | `{"success": true, "mock": true}` — and prints `>>> CAPTURED APPROVAL UPDATE: state=...` |
| `POST /.../token` | `{"access_token": "mock-access-token", "token_type": "Bearer", "expires_in": 3600}` |
| `POST /.../order_now` | `{"result": {"request_number": "REQ-MOCK-001", "sys_id": "mock-sys-id"}}` |
| Anything else | `{"mock": true, "path": "..."}` |

The mock accepts **any** `Authorization` header — token validation is the webhook service's job, not the mock's.

## Wiring the webhook service to this mock

Use `../../.env-mock` as a drop-in replacement for `.env`:

```bash
cp .env-mock .env     # from repo root
```

Or manually set these and restart the service:

```
AXWAY_API_BASE_URL=http://localhost:9090
AXWAY_API_TOKEN=mock-token
HIGHMARK_OAUTH_TOKEN_URL=http://localhost:9090/oauth2/rest/token
HIGHMARK_CATALOG_BASE_URL=http://localhost:9090/snow/v1
```

## Full end-to-end flow

See [`E2E_TESTING.md`](../../E2E_TESTING.md) at the repo root for the complete three-terminal workflow (mock server / webhook service / interactive driver) and verification queries.
