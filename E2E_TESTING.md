# End-to-End Testing Guide

This guide walks through running the webhook service against a local mock that
simulates Axway Amplify, Highmark OAuth, and the Highmark/ServiceNow Catalog
API — so you can exercise the full approval/rejection flow without touching
real upstream systems.

---

## What gets tested

The full round-trip:

1. **Axway Amplify → service** — synthetic `ResourceCreated` webhook is POSTed to `/webhooks/axway`.
2. **Service stores the event** in H2 (`source=AXWAY`, `status=PROCESSED`).
3. **You decide** approve or reject (standing in for ServiceNow).
4. **ServiceNow → service** — corresponding `change.approved` / `change.rejected` callback POSTed to `/webhooks/servicenow`.
5. **Service → mock Axway** — service resolves the original event's `selfLink` and PUTs to `{baseUrl}/apis{selfLink}/approval` with the Amplify-shaped payload.
6. **Mock Axway captures** the approval update and prints it to its terminal.
7. **DB tracks the outcome**: `approval_state` reflects the ServiceNow decision; `callback_status` reflects whether the Axway PUT succeeded.

---

## Files

| Path | Purpose |
|---|---|
| `test-scripts/mock-server/` | `uv`-runnable Python project. `mock_server.py` pretends to be Axway, Highmark OAuth, and Highmark Catalog; logs every inbound request. Has `pyproject.toml` + PEP 723 inline metadata, so zero manual venv setup. |
| `test-scripts/test-e2e-interactive.sh` | Driver that seeds an Axway event, prompts you for approve/reject, then sends the matching ServiceNow callback. Loops so you can run multiple rounds. Accepts `--env-file <path>` / `-e <path>` / `ENV_FILE=<path>`. |
| `test-scripts/test-e2e-approval-flow.sh` | Non-interactive variant — runs both approve and reject scenarios in one shot. Useful for a quick smoke test. Accepts the same `--env-file` flag. |
| `.env-mock` | A complete `.env` file pre-configured to point all upstream URLs at the local mock. |

---

## Point the service at the mock

No file juggling required — `start-webhook-service.sh` accepts an `--env-file` flag:

```bash
./start-webhook-service.sh --env-file .env-mock
```

The `.env-mock` file (at repo root):

- Points `AXWAY_API_BASE_URL`, `HIGHMARK_OAUTH_TOKEN_URL`, and `HIGHMARK_CATALOG_BASE_URL` at `http://localhost:9090`.
- Disables `AXWAY_OAUTH_ENABLED` (uses static `AXWAY_API_TOKEN=mock-token` instead).
- Disables email so it doesn't try to talk to a real SMTP server.
- Removes `axway` from `OAUTH_STARTUP_REQUIRED_SERVICES` so startup doesn't fail before the mock is reachable.

Your real `.env` stays untouched.

---

## Running a session

You'll need three terminals.

### Terminal 1 — start the mock upstream

```bash
uv run test-scripts/mock-server/mock_server.py
```

`uv` handles the Python environment automatically (no `python3`, no venv). The script has a PEP 723 header declaring `requires-python = ">=3.10"` with no dependencies, so `uv run` is zero-setup. You can also run it from inside the folder (`cd test-scripts/mock-server && uv run mock_server.py`) or, after `uv sync`, use the declared entry point (`uv run mock-server`).

You should see:

```
Mock Axway/Highmark server listening on http://localhost:9090
Set the following in .env, then restart the webhook service:
  AXWAY_API_BASE_URL=http://localhost:9090
  AXWAY_API_TOKEN=mock-token
  HIGHMARK_OAUTH_TOKEN_URL=http://localhost:9090/oauth2/rest/token
  HIGHMARK_CATALOG_BASE_URL=http://localhost:9090/snow/v1

Press Ctrl-C to stop.
------------------------------------------------------------
```

Leave it running. Every request the webhook service makes will be logged here.

### Terminal 2 — start the webhook service

```bash
./start-webhook-service.sh --env-file .env-mock
# short form
./start-webhook-service.sh -e .env-mock
# via env var
ENV_FILE=.env-mock ./start-webhook-service.sh
```

Wait for it to print `Started WebhookServiceApplication`. The OAuth startup
health check will probe the mock — you should see a request appear in
Terminal 1 within a few seconds of startup.

> If you prefer `./gradlew bootRun`, note that Gradle doesn't load `.env`
> files. Either export the vars into your shell first
> (`set -a; source .env-mock; set +a; ./gradlew bootRun`) or use the
> `start-webhook-service.sh --env-file` form above.

### Terminal 3 — drive the test

Pass the **same** env file the service is using so their `AXWAY_WEBHOOK_TOKEN`
values agree (otherwise the Axway POST will come back `Axway webhook
authentication failed`):

```bash
./test-scripts/test-e2e-interactive.sh --env-file .env-mock
# short form
./test-scripts/test-e2e-interactive.sh -e .env-mock
# or via env var
ENV_FILE=.env-mock ./test-scripts/test-e2e-interactive.sh
```

You should see `Loading env from: .../.env-mock` at the start, confirming the
token came from the same file the service is running with.

For each round you'll see:

```
=== New round ===
→ Sending Axway ResourceCreated → http://localhost:8080/webhooks/axway
✓ Axway event accepted (HTTP 200)
    eventId=e2e-int-1745432100  selfLink=/management/v1alpha1/environments/e2e-env/assetrequests/e2e-int-1745432100

Simulating ServiceNow: what's your decision?
  [a] approve
  [r] reject
  [s] skip this round
Choice:
```

Type `a` or `r`. The script POSTs the matching ServiceNow callback and prints
hints for what to look for in the other terminals.

After each round it prompts whether to run another, so you can do many rounds
in a single session.

---

## What you should see

### In Terminal 1 (mock server) for an approval

```
[09:14:22.103] POST /oauth2/rest/token
  Authorization: Basic bW9jay1jbGllbnQ6…
  Body (raw): grant_type=client_credentials&scope=resource.READ

[09:14:22.456] PUT /apis/management/v1alpha1/environments/e2e-env/assetrequests/e2e-int-1745432100/approval
  Authorization: Bearer mock-access-token…
  Body:
    {
      "approval": {
        "state": {
          "name": "approved",
          "reason": "from code"
        }
      }
    }
  >>> CAPTURED APPROVAL UPDATE: state=approved on /apis/...
```

The `>>> CAPTURED APPROVAL UPDATE` line is the proof that the round-trip
worked end-to-end. For a rejection, `state=rejected`.

### In the H2 console (`http://localhost:8080/h2-console`)

JDBC URL: `jdbc:h2:file:./data/webhook_db`, username `sa`, no password.

```sql
SELECT event_id, source, event_type, status,
       approval_state, callback_status, callback_attempted_at
FROM event_records
WHERE event_id LIKE 'e2e-int-%' OR correlation_id LIKE 'e2e-int-%'
ORDER BY received_at DESC;
```

Two rows per round:

| event_id | source | event_type | status | approval_state | callback_status |
|---|---|---|---|---|---|
| (snow request_number) | SERVICENOW | change.approved | PROCESSED | (null) | (null) |
| e2e-int-… | AXWAY | ResourceCreated | PROCESSED | APPROVED | SUCCESS |

`callback_status = SUCCESS` confirms the mock returned 200 for the PUT.
`approval_state` reflects the ServiceNow decision and is set **regardless** of
whether the Axway callback succeeds.

---

## Failure modes — what each tells you

| Symptom | Likely cause |
|---|---|
| `callback_status = FAILED_API_CALL` | Mock server isn't running, or `AXWAY_API_BASE_URL` doesn't match the mock's port. |
| `callback_status = FAILED_NO_SELFLINK` | The seeded Axway event didn't include `payload.metadata.selfLink`. The interactive script always sets it; if you're sending custom payloads, check the structure. |
| `callback_status = FAILED_EXCEPTION` | Mock returned non-2xx, or the URL contained characters rejected by `validateSelfLink` (e.g., `..`, `?`). Check Terminal 1's body and Terminal 2's logs. |
| `approval_state` stays NULL | No matching Axway event was found for the ServiceNow `correlation_id`. Make sure the ServiceNow `data.correlation_id` equals the Axway event's top-level `id`. |
| Mock receives no `PUT /apis.../approval` | ServiceNow callback was accepted but the lookup by `correlation_id` failed. Same fix as above. |
| Service won't start | OAuth startup health check is failing. The mock-friendly `.env` removes `axway` from required services for this reason. If still failing, set `OAUTH_STARTUP_HEALTH_CHECK_ENABLED=false` temporarily. |

---

## Non-interactive variant

If you just want a quick pass/fail smoke test (e.g., in CI) without the
prompts, use the non-interactive driver. It runs one approval and one
rejection back-to-back:

Pass `--env-file` so the test uses the same token as the service. The
scenario name is a positional arg:

```bash
./test-scripts/test-e2e-approval-flow.sh --env-file .env-mock                    # both
./test-scripts/test-e2e-approval-flow.sh --env-file .env-mock approve            # just approve
./test-scripts/test-e2e-approval-flow.sh --env-file .env-mock reject             # just reject
```

Same expectations: watch Terminal 1 for the captured approval updates and run
the H2 query above to verify DB state.

---

## How the mock decides what to return

`test-scripts/mock-server/mock_server.py` uses path-based routing:

| Request | Response |
|---|---|
| `GET /apis/.../assetrequests/...` | Fake AssetRequest JSON |
| `GET /team/...` | Fake team JSON |
| `GET /user/...` | Fake user JSON |
| `PUT /.../approval` | `{"success": true, "mock": true}` + `>>> CAPTURED` log line |
| `POST /.../token` | `{"access_token": "mock-access-token", ...}` |
| `POST /.../order_now` | `{"result": {"request_number": "REQ-MOCK-001", ...}}` |
| Anything else | `{"mock": true, "path": "..."}` |

It accepts **any** Authorization header — token validation is the webhook
service's job, not the mock's. Add new routes by editing the `do_GET` /
`do_PUT` / `do_POST` methods.

---

## Cleanup

When done:

```bash
# Terminal 1: Ctrl-C the mock server
# Terminal 2: Ctrl-C the webhook service
```

Since the `--env-file .env-mock` flag only affected the running process, your
real `.env` was never touched — no restore needed.

Optional: clear test rows from the DB. In the H2 console:

```sql
DELETE FROM event_records WHERE event_id LIKE 'e2e-%' OR correlation_id LIKE 'e2e-%';
```
