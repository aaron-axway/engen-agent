#!/bin/bash

# ServiceNow Webhook Test Scripts
# Test various ServiceNow webhook events and authentication methods

BASE_URL="http://localhost:8080"
WEBHOOK_ENDPOINT="${BASE_URL}/webhooks/servicenow"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration (set these environment variables)
SERVICENOW_USERNAME=${SERVICENOW_WEBHOOK_USERNAME:-"webhook-user"}
SERVICENOW_PASSWORD=${SERVICENOW_WEBHOOK_PASSWORD:-"webhook-pass"}
SERVICENOW_SECRET=${SERVICENOW_WEBHOOK_SECRET:-"your-hmac-secret-here"}

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

# Function to calculate HMAC-SHA256 signature
calculate_hmac() {
    local payload="$1"
    local secret="$2"
    echo -n "$payload" | openssl dgst -sha256 -hmac "$secret" -hex | sed 's/^.* //'
}

# Function to encode credentials for Basic auth
encode_basic_auth() {
    local username="$1"
    local password="$2"
    echo -n "${username}:${password}" | base64
}

# Test 1: Basic Authentication
test_basic_auth() {
    print_header "Testing Basic Authentication"
    
    payload='{
        "eventId": "snow-basic-test-001",
        "eventType": "incident.created",
        "correlationId": "corr-basic-001",
        "payload": {
            "incidentId": "INC0001234",
            "priority": "2 - High",
            "shortDescription": "Test incident for basic auth",
            "state": "New",
            "assignedTo": "system.admin"
        },
        "timestamp": "2025-01-01T10:00:00Z"
    }'
    
    auth_header=$(encode_basic_auth "$SERVICENOW_USERNAME" "$SERVICENOW_PASSWORD")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Basic authentication successful"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Basic authentication failed with code: $http_code"
        echo "Response: $body"
    fi
}

# Test 2: HMAC Signature Authentication
test_hmac_auth() {
    print_header "Testing HMAC Signature Authentication"
    
    payload='{
        "eventId": "snow-hmac-test-001",
        "eventType": "incident.updated",
        "correlationId": "corr-hmac-001",
        "payload": {
            "incidentId": "INC0001235",
            "priority": "3 - Moderate",
            "shortDescription": "Test incident for HMAC auth",
            "state": "In Progress",
            "assignedTo": "john.doe",
            "workNotes": "Updated via webhook test"
        },
        "timestamp": "2025-01-01T10:05:00Z"
    }'
    
    signature=$(calculate_hmac "$payload" "$SERVICENOW_SECRET")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "X-ServiceNow-Signature: $signature" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "HMAC signature authentication successful"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "HMAC signature authentication failed with code: $http_code"
        echo "Response: $body"
        print_warning "Calculated signature: $signature"
    fi
}

# Test 3: Incident Created Event
test_incident_created() {
    print_header "Testing Incident Created Event"
    
    payload='{
        "eventId": "snow-inc-create-001",
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
    
    auth_header=$(encode_basic_auth "$SERVICENOW_USERNAME" "$SERVICENOW_PASSWORD")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Incident created event processed"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Incident created event failed with code: $http_code"
    fi
}

# Test 4: Change Request Event
test_change_request() {
    print_header "Testing Change Request Event"
    
    payload='{
        "eventId": "snow-chg-req-001",
        "eventType": "change.requested",
        "correlationId": "workflow-001",
        "payload": {
            "changeId": "CHG0001234",
            "number": "CHG0001234",
            "type": "Standard",
            "category": "Software",
            "priority": "2 - High",
            "risk": "Medium",
            "shortDescription": "Deploy new payment API version",
            "description": "Deploy payment API v2.1.0 with enhanced security features",
            "justification": "Security improvements and bug fixes",
            "implementationPlan": "Rolling deployment during maintenance window",
            "rollbackPlan": "Revert to previous version if issues arise",
            "requestedBy": "payments-team",
            "assignmentGroup": "api-deployment-team",
            "plannedStartDate": "2025-01-02T02:00:00Z",
            "plannedEndDate": "2025-01-02T04:00:00Z",
            "state": "Requested",
            "approvalRequired": true,
            "relatedIncidents": [],
            "affectedCIs": ["payment-api", "payment-database", "load-balancer"]
        },
        "timestamp": "2025-01-01T10:15:00Z"
    }'
    
    auth_header=$(encode_basic_auth "$SERVICENOW_USERNAME" "$SERVICENOW_PASSWORD")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Change request event processed"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Change request event failed with code: $http_code"
    fi
}

# Test 5: Change Approval Event (Critical for Axway integration)
test_change_approval() {
    print_header "Testing Change Approval Event"
    
    payload='{
        "eventId": "snow-chg-app-001",
        "eventType": "change.approved",
        "correlationId": "workflow-001",
        "payload": {
            "changeId": "CHG0001234",
            "number": "CHG0001234",
            "state": "Approved",
            "approvedBy": "change.manager@company.com",
            "approvedDate": "2025-01-01T14:30:00Z",
            "approval_comments": "Approved after security review. Deployment can proceed as planned.",
            "comments": "Security team has reviewed and approved the changes. All documentation is complete.",
            "approvalGroup": "Change Advisory Board",
            "riskAssessment": "Low risk - well tested changes",
            "nextState": "Scheduled",
            "scheduledFor": "2025-01-02T02:00:00Z",
            "data": {
                "originalRequestId": "req-api-001",
                "relatedAxwayRequest": "workflow-001"
            }
        },
        "timestamp": "2025-01-01T14:30:00Z"
    }'
    
    auth_header=$(encode_basic_auth "$SERVICENOW_USERNAME" "$SERVICENOW_PASSWORD")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Change approval event processed (this should trigger Axway callback)"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Change approval event failed with code: $http_code"
    fi
}

# Test 6: Incident Resolved Event
test_incident_resolved() {
    print_header "Testing Incident Resolved Event"
    
    payload='{
        "eventId": "snow-inc-res-001",
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
    
    auth_header=$(encode_basic_auth "$SERVICENOW_USERNAME" "$SERVICENOW_PASSWORD")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Incident resolved event processed"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Incident resolved event failed with code: $http_code"
    fi
}

# Test 7: Invalid Authentication
test_invalid_auth() {
    print_header "Testing Invalid Authentication"
    
    payload='{"eventId": "test", "eventType": "incident.created"}'
    
    # Test without authentication
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "403" ]; then
        print_success "Correctly rejected request without authentication"
    else
        print_error "Should have rejected request without authentication (got $http_code)"
    fi
    
    # Test with invalid basic auth
    invalid_auth=$(encode_basic_auth "wrong-user" "wrong-pass")
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $invalid_auth" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "403" ]; then
        print_success "Correctly rejected request with invalid basic auth"
    else
        print_error "Should have rejected request with invalid basic auth (got $http_code)"
    fi
}

# Test 8: Unknown Event Type
test_unknown_event() {
    print_header "Testing Unknown Event Type"
    
    payload='{
        "eventId": "snow-unknown-001",
        "eventType": "unknown.event.type",
        "correlationId": "workflow-unknown-001",
        "payload": {
            "data": "test"
        },
        "timestamp": "2025-01-01T10:20:00Z"
    }'
    
    auth_header=$(encode_basic_auth "$SERVICENOW_USERNAME" "$SERVICENOW_PASSWORD")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
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
    
    if [ "$SERVICENOW_USERNAME" = "webhook-user" ]; then
        print_warning "Using default username. Set SERVICENOW_WEBHOOK_USERNAME environment variable for real testing."
    fi
    
    if [ "$SERVICENOW_PASSWORD" = "webhook-pass" ]; then
        print_warning "Using default password. Set SERVICENOW_WEBHOOK_PASSWORD environment variable for real testing."
    fi
    
    if [ "$SERVICENOW_SECRET" = "your-hmac-secret-here" ]; then
        print_warning "Using default secret. Set SERVICENOW_WEBHOOK_SECRET environment variable for real testing."
    fi
    
    test_basic_auth
    test_hmac_auth
    test_incident_created
    test_change_request
    test_change_approval
    test_incident_resolved
    test_invalid_auth
    test_unknown_event
    
    print_header "Test Summary"
    echo "All ServiceNow webhook tests completed!"
}

# Run tests if script is executed directly
if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi