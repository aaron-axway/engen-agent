#!/bin/bash

# Interactive End-to-End Test
#
# Lets you interactively simulate:
#   1. Axway Amplify sending a webhook to the service
#   2. You picking "approve" or "reject" (standing in for ServiceNow)
#   3. ServiceNow posting the decision back to the service
#   4. The service calling back to Axway to update the approval
#
# Intended to be run alongside the mock server so you can SEE the outbound
# Axway approval update land:
#
#   Terminal 1:  uv run test-scripts/mock-server/mock_server.py
#   Terminal 2:  ./start-webhook-service.sh --env-file .env-mock
#   Terminal 3:  ./test-scripts/test-e2e-interactive.sh --env-file .env-mock
#
# Both service and test must use the SAME env file so their
# AXWAY_WEBHOOK_TOKEN values match.
#
# The script prompts for each decision, so you can run any sequence of
# approve/reject scenarios against a single running service instance.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Default to .env; let --env-file / -e override. Also honors ENV_FILE env var.
ENV_FILE="${ENV_FILE:-.env}"
_args=()
while [[ $# -gt 0 ]]; do
    case "$1" in
        -e|--env-file)
            if [ -z "${2:-}" ]; then
                echo "$1 requires a path argument" >&2
                exit 1
            fi
            ENV_FILE="$2"
            shift 2
            ;;
        *)
            _args+=("$1")
            shift
            ;;
    esac
done
set -- "${_args[@]}"

ENV_PATH="$ENV_FILE"
if [ ! -f "$ENV_PATH" ] && [ -f "${SCRIPT_DIR}/../${ENV_FILE}" ]; then
    ENV_PATH="${SCRIPT_DIR}/../${ENV_FILE}"
fi

if [ -f "$ENV_PATH" ]; then
    echo "Loading env from: $ENV_PATH"
    set -a
    # shellcheck disable=SC1090
    source "$ENV_PATH"
    set +a
else
    echo "Warning: env file '$ENV_FILE' not found — using system environment"
fi

BASE_URL="${BASE_URL:-http://localhost:8080}"
AXWAY_ENDPOINT="${BASE_URL}/webhooks/axway"
SERVICENOW_ENDPOINT="${BASE_URL}/webhooks/servicenow"
AXWAY_TOKEN="${AXWAY_WEBHOOK_TOKEN:-test-axway-token}"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m'

print_header()  { echo -e "\n${BLUE}=== $1 ===${NC}"; }
print_step()    { echo -e "${PURPLE}→ $1${NC}"; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error()   { echo -e "${RED}✗ $1${NC}"; }
print_warning() { echo -e "${YELLOW}⚠ $1${NC}"; }

check_service() {
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/webhooks/health")
    if [ "$code" != "200" ]; then
        print_error "Service not reachable at $BASE_URL (health returned $code)"
        echo "Start it with: ./gradlew bootRun   or   ./start-webhook-service.sh"
        exit 1
    fi
}

seed_axway_event() {
    local event_id="$1"
    local self_link="$2"

    local payload='{
        "id": "'"$event_id"'",
        "time": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'",
        "type": "ResourceCreated",
        "correlationId": "'"$event_id"'",
        "product": "e2e-interactive",
        "payload": {
            "kind": "Asset",
            "metadata": {"selfLink": "'"$self_link"'"}
        }
    }'

    print_step "Sending Axway ResourceCreated → $AXWAY_ENDPOINT"
    local response http_code
    response=$(curl -s -w "\n%{http_code}" -X POST "$AXWAY_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$payload")
    http_code=$(echo "$response" | tail -n1)

    if [ "$http_code" = "200" ]; then
        print_success "Axway event accepted (HTTP 200)"
        echo "    eventId=$event_id  selfLink=$self_link"
    else
        print_error "Axway webhook failed (HTTP $http_code)"
        echo "$response" | sed '$d'
        return 1
    fi
}

send_servicenow_decision() {
    local decision="$1"          # approve | reject
    local correlation_id="$2"

    local event_type approval_status comments
    if [ "$decision" = "approve" ]; then
        event_type="change.approved"
        approval_status="approved"
        comments="Interactive E2E: approved"
    else
        event_type="change.rejected"
        approval_status="rejected"
        comments="Interactive E2E: rejected"
    fi

    local payload='{
        "event": "'"$event_type"'",
        "timestamp": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'",
        "data": {
            "request_number": "REQ-INT-'"$(date +%s)"'",
            "correlation_id": "'"$correlation_id"'",
            "approval_status": "'"$approval_status"'",
            "approved_by": "you@e2e-test.local",
            "comments": "'"$comments"'"
        }
    }'

    print_step "Sending ServiceNow $event_type → $SERVICENOW_ENDPOINT"
    local response http_code body
    response=$(curl -s -w "\n%{http_code}" -X POST "$SERVICENOW_ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$payload")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ]; then
        print_success "ServiceNow callback accepted (HTTP 200)"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "ServiceNow callback failed (HTTP $http_code)"
        echo "$body"
        return 1
    fi
}

run_one_round() {
    local event_id="e2e-int-$(date +%s)"
    local self_link="/management/v1alpha1/environments/e2e-env/assetrequests/${event_id}"

    print_header "New round"
    seed_axway_event "$event_id" "$self_link" || return 1

    echo ""
    echo -e "${YELLOW}Simulating ServiceNow: what's your decision?${NC}"
    echo "  [a] approve"
    echo "  [r] reject"
    echo "  [s] skip this round"
    read -r -p "Choice: " choice

    case "$choice" in
        a|A|approve)
            sleep 1
            send_servicenow_decision "approve" "$event_id"
            ;;
        r|R|reject)
            sleep 1
            send_servicenow_decision "reject" "$event_id"
            ;;
        s|S|skip)
            print_warning "Skipped. Axway event still recorded with no decision."
            return 0
            ;;
        *)
            print_error "Unknown choice '$choice' — skipping this round."
            return 0
            ;;
    esac

    echo ""
    print_step "Look for these in the mock server terminal:"
    echo "    1. POST /oauth2/rest/token                    (token fetch)"
    echo "    2. PUT  /apis${self_link}/approval            ← the decision"
    echo ""
    print_step "Verify in H2 console (${BASE_URL}/h2-console):"
    echo "    SELECT event_id, source, approval_state, callback_status"
    echo "    FROM event_records WHERE event_id = '${event_id}' OR correlation_id = '${event_id}';"
}

main() {
    print_header "Interactive E2E Approval / Rejection Test"
    echo "Base URL: $BASE_URL"
    check_service

    print_warning "For callback_status=SUCCESS you need the mock server running at"
    print_warning "whatever AXWAY_API_BASE_URL is set to (or a real Axway)."

    while true; do
        run_one_round
        echo ""
        read -r -p "Run another round? [y/N] " again
        case "$again" in
            y|Y|yes) continue ;;
            *) break ;;
        esac
    done

    print_header "Done"
    echo "Stop the mock server with Ctrl-C in its terminal."
}

if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi
