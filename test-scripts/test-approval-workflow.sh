#!/bin/bash

# Complete Approval Workflow Test
# Tests the full Axway -> ServiceNow -> Axway approval cycle

BASE_URL="http://localhost:8080"
AXWAY_ENDPOINT="${BASE_URL}/webhooks/axway"
SERVICENOW_ENDPOINT="${BASE_URL}/webhooks/servicenow"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# Configuration
AXWAY_TOKEN=${AXWAY_WEBHOOK_TOKEN:-"test-axway-token"}
SERVICENOW_USERNAME=${SERVICENOW_WEBHOOK_USERNAME:-"snow-user"}
SERVICENOW_PASSWORD=${SERVICENOW_WEBHOOK_PASSWORD:-"snow-pass"}

print_header() {
    echo -e "\n${BLUE}=== $1 ===${NC}"
}

print_step() {
    echo -e "${PURPLE}→ $1${NC}"
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

# Function to encode credentials for Basic auth
encode_basic_auth() {
    local username="$1"
    local password="$2"
    echo -n "${username}:${password}" | base64
}

# Test Scenario 1: Complete API Approval Workflow
test_api_approval_workflow() {
    print_header "Testing Complete API Approval Workflow"
    
    local correlation_id="workflow-api-$(date +%s)"
    local request_id="req-api-$(date +%s)"
    
    print_step "Step 1: Axway sends API creation request requiring approval"
    
    axway_payload='{
        "eventId": "axway-api-approval-001",
        "eventType": "api.created",
        "correlationId": "'$correlation_id'",
        "payload": {
            "requestId": "'$request_id'",
            "apiName": "secure-payment-api",
            "version": "2.0",
            "description": "New secure payment processing API with PCI compliance",
            "owner": "payments-team",
            "approvalRequired": true,
            "data": {
                "id": "'$request_id'",
                "endpoints": [
                    "/api/v2/payments/process",
                    "/api/v2/payments/refund",
                    "/api/v2/payments/status"
                ],
                "securityLevel": "High",
                "complianceRequired": ["PCI-DSS", "SOX"]
            }
        },
        "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
    }'
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$AXWAY_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$axway_payload")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Axway API creation event processed"
    else
        print_error "Axway API creation failed with code: $http_code"
        return 1
    fi
    
    sleep 2
    
    print_step "Step 2: ServiceNow receives change request for API approval"
    
    servicenow_change_payload='{
        "eventId": "snow-change-req-001",
        "eventType": "change.requested",
        "correlationId": "'$correlation_id'",
        "payload": {
            "changeId": "CHG$(date +%s)",
            "type": "Standard",
            "category": "Software",
            "priority": "2 - High",
            "risk": "Medium",
            "shortDescription": "Deploy secure payment API v2.0",
            "description": "Deploy new secure payment API with enhanced PCI compliance features",
            "justification": "Required for PCI-DSS compliance and improved security",
            "requestedBy": "payments-team",
            "assignmentGroup": "security-review-team",
            "state": "Requested",
            "approvalRequired": true,
            "relatedAxwayRequest": "'$request_id'",
            "securityReview": {
                "required": true,
                "reviewType": "Full Security Assessment",
                "complianceCheck": ["PCI-DSS", "SOX"]
            }
        },
        "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
    }'
    
    auth_header=$(encode_basic_auth "$SERVICENOW_USERNAME" "$SERVICENOW_PASSWORD")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$SERVICENOW_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$servicenow_change_payload")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "200" ]; then
        print_success "ServiceNow change request processed"
    else
        print_error "ServiceNow change request failed with code: $http_code"
        return 1
    fi
    
    sleep 3
    
    print_step "Step 3: ServiceNow approves the change request (triggers Axway callback)"
    
    servicenow_approval_payload='{
        "eventId": "snow-approval-001",
        "eventType": "change.approved",
        "correlationId": "'$correlation_id'",
        "payload": {
            "changeId": "CHG$(date +%s)",
            "state": "Approved",
            "approvedBy": "security.manager@company.com",
            "approvedDate": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'",
            "approval_comments": "Security review completed successfully. All PCI-DSS requirements met. API design follows security best practices. Approved for production deployment.",
            "comments": "Comprehensive security assessment conducted:\n- Code security scan: PASSED\n- Infrastructure review: PASSED\n- PCI-DSS compliance: VERIFIED\n- Penetration testing: COMPLETED\nReady for production deployment.",
            "approvalGroup": "Security Review Board",
            "riskAssessment": "Low risk - comprehensive testing completed",
            "nextState": "Scheduled",
            "securityReview": {
                "status": "Approved",
                "reviewer": "security.team@company.com",
                "reviewDate": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'",
                "findings": "No security issues identified",
                "recommendations": "Standard deployment procedures apply"
            },
            "data": {
                "originalRequestId": "'$request_id'",
                "relatedAxwayRequest": "'$correlation_id'"
            }
        },
        "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
    }'
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$SERVICENOW_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$servicenow_approval_payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "ServiceNow approval processed - should trigger Axway API callback"
        echo "Response: $body" | jq '.' 2>/dev/null || echo "Response: $body"
    else
        print_error "ServiceNow approval failed with code: $http_code"
        return 1
    fi
    
    print_step "Workflow completed! Check application logs for Axway callback attempt."
    print_warning "Note: Axway callback will fail if AXWAY_API_BASE_URL and AXWAY_API_TOKEN are not configured."
}

# Test Scenario 2: Subscription Approval Workflow
test_subscription_approval_workflow() {
    print_header "Testing Subscription Approval Workflow"
    
    local correlation_id="workflow-sub-$(date +%s)"
    local request_id="req-sub-$(date +%s)"
    
    print_step "Step 1: Axway subscription request"
    
    axway_sub_payload='{
        "eventId": "axway-sub-approval-001",
        "eventType": "subscription.created",
        "correlationId": "'$correlation_id'",
        "payload": {
            "requestId": "'$request_id'",
            "apiName": "secure-payment-api",
            "subscriber": "enterprise-mobile-app",
            "plan": "enterprise",
            "quotaLimit": 50000,
            "rateLimitPerSecond": 500,
            "data": {
                "id": "'$request_id'",
                "subscriberType": "External",
                "contractValue": "$25000/month",
                "approvalRequired": true,
                "businessJustification": "High-value enterprise client requiring API access"
            }
        },
        "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
    }'
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$AXWAY_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$axway_sub_payload")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Axway subscription request processed"
    else
        print_error "Axway subscription request failed with code: $http_code"
        return 1
    fi
    
    sleep 2
    
    print_step "Step 2: ServiceNow business approval"
    
    servicenow_business_approval='{
        "eventId": "snow-biz-approval-001",
        "eventType": "change.approved",
        "correlationId": "'$correlation_id'",
        "payload": {
            "changeId": "CHG-BIZ-$(date +%s)",
            "state": "Approved",
            "approvedBy": "business.manager@company.com",
            "approvedDate": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'",
            "approval_comments": "Business case approved. Enterprise client contract value justifies the API access level. Proceed with subscription activation.",
            "comments": "Business review completed:\n- Contract value: $25,000/month\n- Client tier: Enterprise\n- Risk assessment: Low\n- Revenue impact: High\nApproved for immediate activation.",
            "approvalGroup": "Business Review Board",
            "data": {
                "originalRequestId": "'$request_id'",
                "subscriptionType": "Enterprise",
                "contractValue": 25000
            }
        },
        "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
    }'
    
    auth_header=$(encode_basic_auth "$SERVICENOW_USERNAME" "$SERVICENOW_PASSWORD")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$SERVICENOW_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Basic $auth_header" \
        -d "$servicenow_business_approval")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "200" ]; then
        print_success "ServiceNow business approval processed - should trigger Axway callback"
    else
        print_error "ServiceNow business approval failed with code: $http_code"
        return 1
    fi
}

# Test Scenario 3: Rejection Workflow
test_rejection_workflow() {
    print_header "Testing Rejection Workflow"
    
    local correlation_id="workflow-reject-$(date +%s)"
    local request_id="req-reject-$(date +%s)"
    
    print_step "Step 1: Axway API request"
    
    axway_reject_payload='{
        "eventId": "axway-reject-test-001",
        "eventType": "api.created",
        "correlationId": "'$correlation_id'",
        "payload": {
            "requestId": "'$request_id'",
            "apiName": "test-legacy-api",
            "version": "1.0",
            "description": "Legacy API with known security issues",
            "owner": "legacy-team",
            "data": {
                "id": "'$request_id'",
                "securityLevel": "Low",
                "deprecated": true
            }
        },
        "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
    }'
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$AXWAY_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$axway_reject_payload")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Axway API request processed"
    else
        print_error "Axway API request failed with code: $http_code"
        return 1
    fi
    
    sleep 2
    
    print_step "Step 2: ServiceNow rejection"
    
    # Note: For rejection workflow, we would send a "change.rejected" event
    # but our current implementation only handles "change.approved"
    # This demonstrates the need for rejection handling
    
    print_warning "Rejection workflow requires implementing 'change.rejected' event type"
    print_step "This would send rejection reason back to Axway API"
}

# Test Database Queries
test_database_queries() {
    print_header "Testing Database Queries via H2 Console"
    
    echo "You can access the H2 console at: ${BASE_URL}/h2-console"
    echo "Database URL: jdbc:h2:file:./data/webhook_db"
    echo "Username: sa"
    echo "Password: (empty)"
    echo ""
    echo "Useful queries to run:"
    echo ""
    echo "-- View all events"
    echo "SELECT * FROM EVENT_RECORDS ORDER BY RECEIVED_AT DESC;"
    echo ""
    echo "-- View events by correlation ID"
    echo "SELECT EVENT_ID, EVENT_TYPE, SOURCE, CORRELATION_ID, STATUS, APPROVAL_STATE"
    echo "FROM EVENT_RECORDS WHERE CORRELATION_ID LIKE 'workflow-%';"
    echo ""
    echo "-- View approval tracking"
    echo "SELECT EVENT_ID, CORRELATION_ID, APPROVAL_STATE, CALLBACK_STATUS, CALLBACK_ATTEMPTED_AT"
    echo "FROM EVENT_RECORDS WHERE APPROVAL_STATE IS NOT NULL;"
    echo ""
    echo "-- View related events"
    echo "SELECT EVENT_ID, RELATED_EVENT_ID, EVENT_TYPE, SOURCE"
    echo "FROM EVENT_RECORDS WHERE RELATED_EVENT_ID IS NOT NULL;"
}

# Performance test with multiple concurrent requests
test_performance() {
    print_header "Testing Performance with Concurrent Requests"
    
    local num_requests=10
    local correlation_base="perf-test-$(date +%s)"
    
    print_step "Sending $num_requests concurrent requests..."
    
    for i in $(seq 1 $num_requests); do
        (
            payload='{
                "eventId": "perf-test-'$i'",
                "eventType": "api.created",
                "correlationId": "'$correlation_base'-'$i'",
                "payload": {
                    "requestId": "req-perf-'$i'",
                    "apiName": "perf-test-api-'$i'",
                    "data": {"id": "req-perf-'$i'"}
                },
                "timestamp": "'$(date -u +%Y-%m-%dT%H:%M:%SZ)'"
            }'
            
            curl -s -X POST "$AXWAY_ENDPOINT" \
                -H "Content-Type: application/json" \
                -H "Authorization: Bearer $AXWAY_TOKEN" \
                -d "$payload" > /dev/null
        ) &
    done
    
    wait
    print_success "Completed $num_requests concurrent requests"
}

# Main execution
main() {
    print_header "Complete Approval Workflow Tests"
    echo "Base URL: $BASE_URL"
    echo "Axway Endpoint: $AXWAY_ENDPOINT"
    echo "ServiceNow Endpoint: $SERVICENOW_ENDPOINT"
    echo ""
    
    if [ "$1" = "api" ]; then
        test_api_approval_workflow
    elif [ "$1" = "subscription" ]; then
        test_subscription_approval_workflow
    elif [ "$1" = "rejection" ]; then
        test_rejection_workflow
    elif [ "$1" = "database" ]; then
        test_database_queries
    elif [ "$1" = "performance" ]; then
        test_performance
    elif [ "$1" = "all" ]; then
        test_api_approval_workflow
        echo ""
        test_subscription_approval_workflow
        echo ""
        test_rejection_workflow
        echo ""
        test_performance
        echo ""
        test_database_queries
    else
        echo "Usage: $0 [api|subscription|rejection|database|performance|all]"
        echo ""
        echo "  api          - Test complete API approval workflow"
        echo "  subscription - Test subscription approval workflow" 
        echo "  rejection    - Test rejection workflow"
        echo "  database     - Show database query examples"
        echo "  performance  - Test with concurrent requests"
        echo "  all          - Run all tests"
        echo ""
        echo "Examples:"
        echo "  $0 api              # Test API approval workflow"
        echo "  $0 all              # Run all tests"
        exit 1
    fi
}

# Run tests if script is executed directly
if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi