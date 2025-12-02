#!/bin/bash

# Axway Amplify Webhook Test Scripts
# Test various Axway webhook events and authentication methods

BASE_URL="http://localhost:8080"
WEBHOOK_ENDPOINT="${BASE_URL}/webhooks/axway"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Configuration (set these environment variables)
AXWAY_TOKEN=${AXWAY_WEBHOOK_TOKEN:-"your-bearer-token-here"}
AXWAY_SECRET=${AXWAY_WEBHOOK_SECRET:-"your-hmac-secret-here"}

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

# Test 1: Health Check
test_health() {
    print_header "Testing Health Endpoint"
    
    response=$(curl -s -w "\n%{http_code}" "${BASE_URL}/webhooks/health")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Health endpoint responding: $body"
    else
        print_error "Health endpoint failed with code: $http_code"
    fi
}

# Test 2: Bearer Token Authentication
test_bearer_auth() {
    print_header "Testing Bearer Token Authentication"
    
    payload='{
        "eventId": "axway-bearer-test-001",
        "eventType": "api.created",
        "correlationId": "corr-bearer-001",
        "payload": {
            "requestId": "req-bearer-001",
            "apiName": "test-api",
            "description": "Test API creation event"
        },
        "timestamp": "2025-01-01T10:00:00Z"
    }'
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Bearer token authentication successful"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Bearer token authentication failed with code: $http_code"
        echo "Response: $body"
    fi
}

# Test 3: HMAC Signature Authentication
test_hmac_auth() {
    print_header "Testing HMAC Signature Authentication"
    
    payload='{
        "eventId": "axway-hmac-test-001",
        "eventType": "api.updated",
        "correlationId": "corr-hmac-001",
        "payload": {
            "requestId": "req-hmac-001",
            "apiName": "test-api",
            "version": "1.1",
            "changes": ["description updated"]
        },
        "timestamp": "2025-01-01T10:05:00Z"
    }'
    
    signature=$(calculate_hmac "$payload" "$AXWAY_SECRET")
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "X-Axway-Signature: $signature" \
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

# Test 4: API Creation Event
test_api_creation() {
    print_header "Testing API Creation Event"
    
    payload='{
        "eventId": "axway-api-create-001",
        "eventType": "api.created",
        "correlationId": "workflow-001",
        "payload": {
            "requestId": "req-api-001",
            "apiName": "payment-api",
            "version": "1.0",
            "description": "Payment processing API",
            "owner": "payments-team",
            "data": {
                "id": "req-api-001",
                "approvalRequired": true,
                "endpoints": [
                    "/api/v1/payments",
                    "/api/v1/refunds"
                ]
            }
        },
        "timestamp": "2025-01-01T10:10:00Z"
    }'
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "API creation event processed"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "API creation event failed with code: $http_code"
    fi
}

# Test 5: Subscription Creation Event
test_subscription_creation() {
    print_header "Testing Subscription Creation Event"
    
    payload='{
        "eventId": "axway-sub-create-001",
        "eventType": "subscription.created",
        "correlationId": "workflow-sub-001",
        "payload": {
            "requestId": "req-sub-001",
            "apiName": "payment-api",
            "subscriber": "mobile-app",
            "plan": "premium",
            "data": {
                "id": "req-sub-001",
                "quotaLimit": 10000,
                "rateLimitPerSecond": 100
            }
        },
        "timestamp": "2025-01-01T10:15:00Z"
    }'
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | head -n -1)
    
    if [ "$http_code" = "200" ]; then
        print_success "Subscription creation event processed"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Subscription creation event failed with code: $http_code"
    fi
}

# Test 6: Invalid Authentication
test_invalid_auth() {
    print_header "Testing Invalid Authentication"
    
    payload='{"eventId": "test", "eventType": "api.created"}'
    
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
    
    # Test with invalid bearer token
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer invalid-token" \
        -d "$payload")
    
    http_code=$(echo "$response" | tail -n1)
    
    if [ "$http_code" = "403" ]; then
        print_success "Correctly rejected request with invalid bearer token"
    else
        print_error "Should have rejected request with invalid bearer token (got $http_code)"
    fi
}

# Test 7: Unknown Event Type
test_unknown_event() {
    print_header "Testing Unknown Event Type"
    
    payload='{
        "eventId": "axway-unknown-001",
        "eventType": "unknown.event.type",
        "correlationId": "workflow-unknown-001",
        "payload": {
            "data": "test"
        },
        "timestamp": "2025-01-01T10:20:00Z"
    }'
    
    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
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
    print_header "Axway Amplify Webhook Tests"
    echo "Base URL: $BASE_URL"
    echo "Testing against: $WEBHOOK_ENDPOINT"
    
    if [ "$AXWAY_TOKEN" = "your-bearer-token-here" ]; then
        print_warning "Using default token. Set AXWAY_WEBHOOK_TOKEN environment variable for real testing."
    fi
    
    if [ "$AXWAY_SECRET" = "your-hmac-secret-here" ]; then
        print_warning "Using default secret. Set AXWAY_WEBHOOK_SECRET environment variable for real testing."
    fi
    
    test_health
    test_bearer_auth
    test_hmac_auth
    test_api_creation
    test_subscription_creation
    test_invalid_auth
    test_unknown_event
    
    print_header "Test Summary"
    echo "All Axway webhook tests completed!"
}

# Run tests if script is executed directly
if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    main "$@"
fi