#!/bin/bash

# Test script for Axway AssetRequest webhook - triggers ServiceNow catalog creation
# This simulates the webhook event that Amplify sends when an AssetRequest is created

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration
BASE_URL="${BASE_URL:-http://localhost:8080}"
AXWAY_TOKEN="${AXWAY_WEBHOOK_TOKEN:-test-token-123}"

print_header() {
    echo -e "\n${BLUE}=== $1 ===${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

# Test 1: AssetRequest Created Event (triggers ServiceNow catalog item creation)
test_assetrequest_created() {
    print_header "Testing AssetRequest Created Event"
    print_info "This event triggers: Auto-approve + ServiceNow Catalog Item Creation"

    EVENT_ID="evt-$(date +%s)-$$"
    CORRELATION_ID="corr-$(date +%s)-$$"
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.000+0000")
    SELFLINK="/management/v1alpha1/assetrequests/test-asset-request-$(date +%s)"

    # Amplify Native Format - this is what Amplify actually sends
    # Controller detects 'type' and 'product' fields and routes through AxwayWebhookEvent
    WEBHOOK_PAYLOAD=$(cat <<EOF
{
  "id": "$EVENT_ID",
  "time": "$TIMESTAMP",
  "version": "v1",
  "product": "AmplifyCentral",
  "correlationId": "$CORRELATION_ID",
  "organization": {
    "id": "100000142"
  },
  "type": "ResourceCreated",
  "payload": {
    "kind": "AssetRequest",
    "apiVersion": "v1alpha1",
    "name": "test-asset-request",
    "metadata": {
      "id": "assetreq-$(date +%s)",
      "selfLink": "$SELFLINK",
      "references": [
        {
          "kind": "Asset",
          "selfLink": "/management/v1alpha1/assets/test-asset-001",
          "name": "Test API Asset"
        }
      ],
      "audit": {
        "createUserId": "user-test-123",
        "createTimestamp": "$TIMESTAMP"
      },
      "scope": {
        "id": "env-prod-001",
        "kind": "Environment",
        "name": "production"
      }
    },
    "spec": {
      "access": {
        "assetResource": "test-asset-001"
      }
    }
  }
}
EOF
)

    print_info "Sending AssetRequest event to $BASE_URL/webhooks/axway"
    echo "Payload:"
    echo "$WEBHOOK_PAYLOAD" | jq . 2>/dev/null || echo "$WEBHOOK_PAYLOAD"
    echo ""

    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/webhooks/axway" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -H "X-Axway-Event-Id: $EVENT_ID" \
        -d "$WEBHOOK_PAYLOAD" 2>/dev/null)

    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
    BODY=$(echo "$RESPONSE" | head -n-1)

    echo ""
    if [ "$HTTP_CODE" -eq 200 ] || [ "$HTTP_CODE" -eq 201 ]; then
        print_success "AssetRequest event sent successfully (HTTP $HTTP_CODE)"
        if [ -n "$BODY" ]; then
            echo "Response:"
            echo "$BODY" | jq . 2>/dev/null || echo "$BODY"
        fi
        return 0
    else
        print_error "AssetRequest event failed (HTTP $HTTP_CODE)"
        if [ -n "$BODY" ]; then
            echo "Error response:"
            echo "$BODY" | jq . 2>/dev/null || echo "$BODY"
        fi
        return 1
    fi
}

# Test 2: SubResourceUpdated Event (typically ignored)
test_subresource_updated() {
    print_header "Testing SubResourceUpdated Event (Should be Ignored)"

    EVENT_ID="evt-sub-$(date +%s)-$$"
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

    WEBHOOK_PAYLOAD=$(cat <<EOF
{
  "id": "$EVENT_ID",
  "eventType": "SubResourceUpdated",
  "kind": "AssetRequest",
  "selfLink": "/management/v1alpha1/assetrequests/test-asset-request",
  "timestamp": "$TIMESTAMP",
  "source": "AmplifyCentral",
  "references": [],
  "payload": {
    "subresource": "approval",
    "state": "pending"
  }
}
EOF
)

    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/webhooks/axway" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$WEBHOOK_PAYLOAD" 2>/dev/null)

    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)

    if [ "$HTTP_CODE" -eq 200 ]; then
        print_success "SubResourceUpdated handled (likely ignored per config)"
    else
        print_error "Unexpected response: HTTP $HTTP_CODE"
    fi
}

# Test 3: Check event storage in database
test_check_events() {
    print_header "Checking Stored Events"

    RESPONSE=$(curl -s "$BASE_URL/actuator/health" 2>/dev/null)

    if echo "$RESPONSE" | jq -e '.status == "UP"' > /dev/null 2>&1; then
        print_success "Service is healthy"
        echo "$RESPONSE" | jq '.components.db // "DB info not available"' 2>/dev/null
    else
        print_error "Service health check failed"
        echo "$RESPONSE"
    fi

    # Try H2 console info
    print_info "To view events, access H2 console at: $BASE_URL/h2-console"
    print_info "JDBC URL: jdbc:h2:file:./data/webhook_db"
    print_info "Query: SELECT * FROM EVENT_RECORDS ORDER BY RECEIVED_AT DESC;"
}

# Main menu
show_menu() {
    echo ""
    print_header "AssetRequest Webhook Test Menu"
    echo "1) Test AssetRequest Created (triggers catalog creation)"
    echo "2) Test SubResourceUpdated (should be ignored)"
    echo "3) Check service health & events"
    echo "4) Run all tests"
    echo "5) Exit"
    echo ""
}

# Run all tests
run_all_tests() {
    print_header "Running All AssetRequest Tests"

    local success=0
    local fail=0

    if test_assetrequest_created; then
        ((success++))
    else
        ((fail++))
    fi

    sleep 1

    test_subresource_updated
    ((success++))

    sleep 1

    test_check_events

    print_header "Test Summary"
    print_success "Tests completed: $((success + fail))"
    if [ $fail -gt 0 ]; then
        print_error "Failed: $fail"
    fi
}

# Main
main() {
    print_header "Axway AssetRequest Webhook Tester"
    print_info "Base URL: $BASE_URL"
    print_info "Token: ${AXWAY_TOKEN:0:10}..."
    echo ""
    print_info "This script tests the AssetRequest webhook flow that triggers:"
    print_info "  1. Fetch AssetRequest details from Axway API"
    print_info "  2. Fetch referenced Asset details"
    print_info "  3. Get Team and API Access Managers"
    print_info "  4. Auto-approve the AssetRequest"
    print_info "  5. Create ServiceNow Catalog Item via Highmark API"

    if [ "$1" = "all" ]; then
        run_all_tests
        exit $?
    fi

    while true; do
        show_menu
        read -p "Select option: " choice

        case $choice in
            1) test_assetrequest_created ;;
            2) test_subresource_updated ;;
            3) test_check_events ;;
            4) run_all_tests ;;
            5) print_info "Exiting..."; exit 0 ;;
            *) print_error "Invalid option" ;;
        esac
    done
}

# Handle arguments
case "$1" in
    "all")
        main "all"
        ;;
    "-h"|"--help"|"help")
        echo "Usage: $0 [all|help]"
        echo ""
        echo "Test the AssetRequest webhook that triggers ServiceNow catalog creation"
        echo ""
        echo "Options:"
        echo "  all     Run all tests automatically"
        echo "  help    Show this help"
        echo ""
        echo "Environment variables:"
        echo "  BASE_URL              Service URL (default: http://localhost:8080)"
        echo "  AXWAY_WEBHOOK_TOKEN   Bearer token for authentication"
        echo ""
        echo "NOTE: This test sends a simulated webhook. The actual Axway API calls"
        echo "      (getResourceBySelflink, getTeam, getUser) will fail unless you"
        echo "      have the Axway API configured and accessible."
        ;;
    *)
        main
        ;;
esac
