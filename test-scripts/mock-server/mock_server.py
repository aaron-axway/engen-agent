#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""
Mock Axway / Highmark server for end-to-end testing of the webhook service.

Pretends to be:
  - Axway Amplify Central API  (GET resources, PUT approval updates)
  - Highmark OAuth token endpoint  (returns a fake Bearer token)
  - Highmark ServiceNow Catalog API  (returns a fake request_number)

Captures every inbound request and prints method + path + body to stdout so
you can visually confirm the webhook service is calling what you expect — in
particular the PUT {selfLink}/approval call triggered by a ServiceNow
approval/rejection webhook.

Usage (with uv — no manual venv needed):
    uv run test-scripts/mock-server/mock_server.py

Or from inside the folder:
    cd test-scripts/mock-server
    uv run mock_server.py

Or using the script entry point declared in pyproject.toml:
    cd test-scripts/mock-server
    uv run mock-server

Point the webhook service at it by setting these in .env (or use .env-mock):
    AXWAY_API_BASE_URL=http://localhost:9090
    AXWAY_API_TOKEN=mock-token
    HIGHMARK_OAUTH_TOKEN_URL=http://localhost:9090/oauth2/rest/token
    HIGHMARK_CATALOG_BASE_URL=http://localhost:9090/snow/v1
"""

import json
import sys
from datetime import datetime
from http.server import BaseHTTPRequestHandler, HTTPServer


class MockHandler(BaseHTTPRequestHandler):
    def _log_request(self):
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length).decode("utf-8") if length else ""

        print(f"\n[{ts}] {self.command} {self.path}")
        auth = self.headers.get("Authorization", "")
        if auth:
            redacted = auth[:20] + "…" if len(auth) > 20 else auth
            print(f"  Authorization: {redacted}")
        if raw:
            try:
                pretty = json.dumps(json.loads(raw), indent=2)
                print("  Body:")
                for line in pretty.splitlines():
                    print(f"    {line}")
            except Exception:
                print(f"  Body (raw): {raw}")
        return raw

    def _respond(self, status, body):
        encoded = json.dumps(body).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)

    def do_GET(self):
        self._log_request()
        if "/assetrequests/" in self.path:
            self._respond(200, {
                "name": "mock-asset-request",
                "metadata": {"selfLink": self.path.replace("/apis", "", 1)},
                "owner": {"id": "mock-team-id"},
            })
        elif "/team/" in self.path:
            self._respond(200, {"result": {"id": "mock-team", "name": "mock-team", "users": []}})
        elif "/user/" in self.path:
            self._respond(200, {"result": {"guid": "mock-user", "email": "mock@example.com"}})
        else:
            self._respond(200, {"mock": True, "path": self.path})

    def do_PUT(self):
        body = self._log_request()
        if "/approval" in self.path:
            try:
                parsed = json.loads(body) if body else {}
                state = (
                    parsed.get("approval", {})
                          .get("state", {})
                          .get("name", "(unknown)")
                )
                print(f"  >>> CAPTURED APPROVAL UPDATE: state={state} on {self.path}")
            except Exception:
                print(f"  >>> CAPTURED APPROVAL UPDATE on {self.path} (body not parseable)")
            self._respond(200, {"success": True, "mock": True})
        else:
            self._respond(200, {"mock": True, "path": self.path})

    def do_POST(self):
        self._log_request()
        if "/token" in self.path:
            self._respond(200, {
                "access_token": "mock-access-token",
                "token_type": "Bearer",
                "expires_in": 3600,
            })
        elif "/order_now" in self.path:
            self._respond(200, {
                "result": {"request_number": "REQ-MOCK-001", "sys_id": "mock-sys-id"}
            })
        else:
            self._respond(200, {"mock": True, "path": self.path})

    # Silence default one-line access log — _log_request is richer
    def log_message(self, fmt, *args):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9090
    server = HTTPServer(("0.0.0.0", port), MockHandler)
    print(f"Mock Axway/Highmark server listening on http://localhost:{port}")
    print("Set the following in .env (or use .env-mock), then restart the webhook service:")
    print(f"  AXWAY_API_BASE_URL=http://localhost:{port}")
    print(f"  AXWAY_API_TOKEN=mock-token")
    print(f"  HIGHMARK_OAUTH_TOKEN_URL=http://localhost:{port}/oauth2/rest/token")
    print(f"  HIGHMARK_CATALOG_BASE_URL=http://localhost:{port}/snow/v1")
    print("")
    print("Press Ctrl-C to stop.")
    print("-" * 60)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down mock server.")
        server.server_close()


if __name__ == "__main__":
    main()
