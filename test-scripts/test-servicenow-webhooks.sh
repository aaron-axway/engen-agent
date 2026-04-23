#!/bin/bash

# ServiceNow Webhook Test Scripts
# The /webhooks/servicenow endpoint is public (no authentication) — callers only
# need to send a valid JSON payload. Network-level controls are expected to
# restrict access in deployed environments.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BASE_URL="http://localhost:8080"
WEBHOOK_ENDPOINT="${BASE_URL}/webhooks/servicenow"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_header() {
    echo -e "\n${BLUE}=== $1 ===${NC}"
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

# Test: New ServiceNow format — Change Approved
test_change_approved_new_format() {
    print_header "Testing Change Approved (new ServiceNow format)"

    payload='{
        "event": "change.approved",
        "timestamp": "2025-11-18T14:30:00Z",
        "data": {
            "request_number": "REQ0012345",
            "correlation_id": "corr-123abc",
            "approval_status": "approved",
            "approved_by": "john.doe@company.com",
            "comments": "API access approved for production use"
        }
    }'

    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$payload")

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ]; then
        print_success "Change approved event processed (this should trigger Axway callback)"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Change approved event failed with code: $http_code"
        echo "Response: $body"
    fi
}

# Test: New ServiceNow format — Change Rejected
test_change_rejected_new_format() {
    print_header "Testing Change Rejected (new ServiceNow format)"

    payload='{
        "event": "change.rejected",
        "timestamp": "2025-11-18T14:30:00Z",
        "data": {
            "request_number": "REQ0012345",
            "correlation_id": "corr-123abc",
            "approval_status": "rejected",
            "approved_by": "john.doe@company.com",
            "comments": "API access rejected for production use"
        }
    }'

    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$payload")

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ]; then
        print_success "Change rejected event processed (this should trigger Axway rejection callback)"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Change rejected event failed with code: $http_code"
        echo "Response: $body"
    fi
}

# Test: Legacy generic format — Incident Created
test_incident_created() {
    print_header "Testing Incident Created Event (legacy generic format)"

    payload='{
        "id": "snow-inc-create-001",
        "eventType": "incident.created",
        "correlationId": "workflow-inc-001",
        "payload": {
            "incidentId": "INC0001240",
            "number": "INC0001240",
            "priority": "1 - Critical",
            "urgency": "1 - High",
            "impact": "1 - High",
            "shortDescription": "Production API gateway down",
            "description": "The main API gateway is not responding to requests",
            "state": "New",
            "category": "Software",
            "subcategory": "Application",
            "assignedTo": "incident-response-team",
            "caller": "monitoring.system@company.com",
            "location": "Data Center 1",
            "affectedServices": ["payment-api", "user-api", "notification-service"]
        },
        "timestamp": "2025-01-01T10:10:00Z"
    }'

    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$payload")

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ]; then
        print_success "Incident created event processed"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Incident created event failed with code: $http_code"
    fi
}

# Test: Legacy generic format — Incident Resolved
test_incident_resolved() {
    print_header "Testing Incident Resolved Event (legacy generic format)"

    payload='{
        "id": "snow-inc-res-001",
        "eventType": "incident.resolved",
        "correlationId": "workflow-inc-res-001",
        "payload": {
            "incidentId": "INC0001240",
            "number": "INC0001240",
            "state": "Resolved",
            "resolvedBy": "john.doe@company.com",
            "resolvedDate": "2025-01-01T15:45:00Z",
            "resolution": "API gateway service was restarted and is now functioning normally",
            "resolutionCode": "Service Restart",
            "closeCode": "Resolved by Caller",
            "workNotes": "Service monitoring confirmed all endpoints are responding normally",
            "rootCause": "Memory leak in gateway process",
            "preventiveMeasures": "Implemented automated memory monitoring and alerting",
            "timeToResolve": "5h 35m",
            "businessImpact": "High - Payment processing was affected",
            "affectedUsers": 1250
        },
        "timestamp": "2025-01-01T15:45:00Z"
    }'

    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$payload")

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ]; then
        print_success "Incident resolved event processed"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Incident resolved event failed with code: $http_code"
    fi
}

# Test: Unknown event type (should still return 200 — handler logs a warning)
test_unknown_event() {
    print_header "Testing Unknown Event Type"

    payload='{
        "id": "snow-unknown-001",
        "eventType": "unknown.event.type",
        "correlationId": "workflow-unknown-001",
        "payload": {
            "data": "test"
        },
        "timestamp": "2025-01-01T10:20:00Z"
    }'

    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$payload")

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ]; then
        print_success "Unknown event type handled gracefully"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Unknown event type failed with code: $http_code"
    fi
}

# Main execution
main() {
    print_header "ServiceNow Webhook Tests"
    echo "Base URL: $BASE_URL"
    echo "Testing against: $WEBHOOK_ENDPOINT"
    echo "Note: endpoint is public — no authentication required"

    test_change_approved_new_format
    test_change_rejected_new_format
    test_incident_created
    test_incident_resolved
    test_unknown_event

    print_header "Test Summary"
    echo "All ServiceNow webhook tests completed!"
}

# Run tests if script is executed directly
if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi
