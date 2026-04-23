#!/bin/bash

# End-to-End Approval / Rejection Flow Test
#
# Simulates the full workflow:
#   1. Axway sends a ResourceCreated webhook (kind=Asset, with a selfLink in payload.metadata)
#   2. Service stores the event record
#   3. ServiceNow sends a change.approved or change.rejected callback whose
#      correlation_id matches the Axway event's eventId
#   4. Service resolves selfLink from the stored Axway event and propagates the
#      decision back to Axway via PUT {baseUrl}/apis{selfLink}/approval
#
# NOTE: The synthetic Axway event uses kind=Asset (not AssetRequest) on purpose —
# this bypasses the forward-flow auto-approval / Highmark Catalog submission so
# the test exercises ONLY the ServiceNow callback path.
#
# Usage: $0 [approve|reject|all]

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

print_header() { echo -e "\n${BLUE}=== $1 ===${NC}"; }
print_step()   { echo -e "${PURPLE}→ $1${NC}"; }
print_success() { echo -e "${GREEN}✓ $1${NC}"; }
print_error()  { echo -e "${RED}✗ $1${NC}"; }
print_warning() { echo -e "${YELLOW}⚠ $1${NC}"; }

# Step 1: Send a synthetic Axway ResourceCreated event (kind=Asset) that the
# service will store with a selfLink retrievable later by the ServiceNow callback.
seed_axway_event() {
    local event_id="$1"
    local self_link="$2"

    local payload='{
        "id": "'"$event_id"'",
        "time": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'",
        "type": "ResourceCreated",
        "correlationId": "'"$event_id"'",
        "product": "e2e-test",
        "payload": {
            "kind": "Asset",
            "metadata": {
                "selfLink": "'"$self_link"'"
            }
        }
    }'

    print_step "Seeding Axway event (eventId=$event_id, selfLink=$self_link)"

    local response http_code body
    response=$(curl -s -w "\n%{http_code}" -X POST "$AXWAY_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$payload")

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ]; then
        print_success "Axway event stored (HTTP 200)"
    else
        print_error "Axway webhook failed (HTTP $http_code)"
        echo "$body"
        return 1
    fi
}

# Step 2: Send ServiceNow approval/rejection callback
send_servicenow_decision() {
    local event_type="$1"        # change.approved | change.rejected
    local correlation_id="$2"
    local approval_status="$3"   # approved | rejected
    local comments="$4"

    local payload='{
        "event": "'"$event_type"'",
        "timestamp": "'"$(date -u +%Y-%m-%dT%H:%M:%SZ)"'",
        "data": {
            "request_number": "REQ-E2E-'"$(date +%s)"'",
            "correlation_id": "'"$correlation_id"'",
            "approval_status": "'"$approval_status"'",
            "approved_by": "e2e-test@company.com",
            "comments": "'"$comments"'"
        }
    }'

    print_step "Sending ServiceNow $event_type (correlation_id=$correlation_id)"

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

run_approval_scenario() {
    print_header "Scenario: Approval"

    local event_id="e2e-approve-$(date +%s)"
    local self_link="/management/v1alpha1/environments/e2e-env/assetrequests/${event_id}"

    seed_axway_event "$event_id" "$self_link" || return 1
    sleep 1
    send_servicenow_decision "change.approved" "$event_id" "approved" "E2E approval test" || return 1

    echo ""
    print_step "Expected DB state for event_id=$event_id (source=AXWAY):"
    echo "    approval_state        = APPROVED"
    echo "    callback_status       = SUCCESS   (if Axway API reachable & creds valid)"
    echo "                            FAILED_API_CALL or FAILED_EXCEPTION otherwise"
    echo "    callback_attempted_at ≠ NULL"
}

run_rejection_scenario() {
    print_header "Scenario: Rejection"

    local event_id="e2e-reject-$(date +%s)"
    local self_link="/management/v1alpha1/environments/e2e-env/assetrequests/${event_id}"

    seed_axway_event "$event_id" "$self_link" || return 1
    sleep 1
    send_servicenow_decision "change.rejected" "$event_id" "rejected" "E2E rejection test" || return 1

    echo ""
    print_step "Expected DB state for event_id=$event_id (source=AXWAY):"
    echo "    approval_state        = REJECTED"
    echo "    callback_status       = SUCCESS   (if Axway API reachable & creds valid)"
    echo "                            FAILED_API_CALL or FAILED_EXCEPTION otherwise"
    echo "    callback_attempted_at ≠ NULL"
}

print_verification_instructions() {
    print_header "Verification"
    echo "Open the H2 console: ${BASE_URL}/h2-console"
    echo "  JDBC URL:  jdbc:h2:file:./data/webhook_db"
    echo "  Username:  sa    (no password)"
    echo ""
    echo "Run:"
    echo ""
    echo "  SELECT event_id, event_type, source, status,"
    echo "         approval_state, callback_status, callback_attempted_at"
    echo "  FROM event_records"
    echo "  WHERE event_id LIKE 'e2e-%'"
    echo "  ORDER BY received_at DESC;"
    echo ""
    print_warning "For callback_status = SUCCESS, the service needs valid Axway API"
    print_warning "credentials (OAuth JWT or static AXWAY_API_TOKEN). Without them,"
    print_warning "approval_state still reflects the ServiceNow decision but"
    print_warning "callback_status will be FAILED_API_CALL."
}

main() {
    print_header "End-to-End Approval / Rejection Flow Test"
    echo "Base URL: $BASE_URL"

    local health
    health=$(curl -s -o /dev/null -w "%{http_code}" "${BASE_URL}/webhooks/health")
    if [ "$health" != "200" ]; then
        print_error "Service not reachable at $BASE_URL (health check returned $health)"
        echo "Start it with: ./gradlew bootRun   or   ./start-webhook-service.sh"
        exit 1
    fi

    case "${1:-all}" in
        approve|approval)
            run_approval_scenario
            ;;
        reject|rejection)
            run_rejection_scenario
            ;;
        all)
            run_approval_scenario
            echo ""
            run_rejection_scenario
            ;;
        *)
            echo "Usage: $0 [approve|reject|all]"
            echo ""
            echo "  approve   - Seed Axway event + send change.approved"
            echo "  reject    - Seed Axway event + send change.rejected"
            echo "  all       - Run both scenarios (default)"
            exit 1
            ;;
    esac

    print_verification_instructions
}

if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi
