#!/bin/bash

# Axway Amplify Webhook Test Scripts
# Test various Axway webhook events and authentication methods

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

BASE_URL="http://localhost:8080"
WEBHOOK_ENDPOINT="${BASE_URL}/webhooks/axway"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Load .env from project root if it exists
load_env() {
    local env_file="${SCRIPT_DIR}/../.env"
    if [ -f "$env_file" ]; then
        set -a
        source "$env_file"
        set +a
    fi
}

load_env

# Configuration — .env is loaded above, CLI --token override applied below in arg parsing
AXWAY_TOKEN=${AXWAY_WEBHOOK_TOKEN:-"your-bearer-token-here"}

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

# Test 1: Health Check
test_health() {
    print_header "Testing Health Endpoint"
    
    response=$(curl -s -w "\n%{http_code}" "${BASE_URL}/webhooks/health")
    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')
    
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
    body=$(echo "$response" | sed '$d')
    
    if [ "$http_code" = "200" ]; then
        print_success "Bearer token authentication successful"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "Bearer token authentication failed with code: $http_code"
        echo "Response: $body"
    fi
}

# Test 3: API Creation Event
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
    body=$(echo "$response" | sed '$d')
    
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
    body=$(echo "$response" | sed '$d')
    
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

# Test: Post payload from a JSON file
test_from_file() {
    local file_path="$1"
    print_header "Testing Payload from File: $file_path"

    if [ ! -f "$file_path" ]; then
        print_error "File not found: $file_path"
        return 1
    fi

    # Validate JSON if jq is available
    if command -v jq &> /dev/null; then
        if ! jq empty "$file_path" 2>/dev/null; then
            print_error "Invalid JSON in file: $file_path"
            return 1
        fi
        print_success "JSON validated"
    fi

    response=$(curl -s -w "\n%{http_code}" -X POST "$WEBHOOK_ENDPOINT" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d @"$file_path")

    http_code=$(echo "$response" | tail -n1)
    body=$(echo "$response" | sed '$d')

    if [ "$http_code" = "200" ]; then
        print_success "File payload processed successfully (HTTP $http_code)"
        echo "$body" | jq '.' 2>/dev/null || echo "$body"
    else
        print_error "File payload failed with code: $http_code"
        echo "Response: $body"
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
    print_header "Axway Amplify Webhook Tests"
    echo "Base URL: $BASE_URL"
    echo "Testing against: $WEBHOOK_ENDPOINT"
    
    if [ "$AXWAY_TOKEN" = "your-bearer-token-here" ]; then
        print_warning "Using default token. Set AXWAY_WEBHOOK_TOKEN environment variable for real testing."
    fi
    
    test_health
    test_bearer_auth
    test_api_creation
    test_subscription_creation
    test_invalid_auth
    test_unknown_event
    
    print_header "Test Summary"
    echo "All Axway webhook tests completed!"
}

usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  --file, -f <path>    Post a webhook payload from a JSON file"
    echo "  --token, -t <token>  Override the Axway bearer token"
    echo "  --help, -h           Show this help message"
    echo "  (no args)            Run all built-in test cases"
    echo ""
    echo "Token resolution order:"
    echo "  1. --token/-t CLI argument (highest priority)"
    echo "  2. AXWAY_WEBHOOK_TOKEN from .env or environment"
    echo "  3. Fallback default"
    echo ""
    echo "Examples:"
    echo "  $0                                              # Run all tests"
    echo "  $0 --file test-scripts/test-payload.json        # Post from file"
    echo "  $0 -t my-token -f test-scripts/test-payload.json  # Token + file"
}

# Run tests if script is executed directly
if [ "${BASH_SOURCE[0]}" == "${0}" ]; then
    ACTION=""
    FILE_PATH=""
    CLI_TOKEN=""

    while [ $# -gt 0 ]; do
        case "$1" in
            --token|-t)
                if [ -z "${2:-}" ]; then
                    print_error "Missing token value. Usage: $0 --token <token>"
                    exit 1
                fi
                CLI_TOKEN="$2"
                shift 2
                ;;
            --file|-f)
                if [ -z "${2:-}" ]; then
                    print_error "Missing file path. Usage: $0 --file <path>"
                    exit 1
                fi
                ACTION="file"
                FILE_PATH="$2"
                shift 2
                ;;
            --help|-h)
                usage
                exit 0
                ;;
            *)
                print_error "Unknown option: $1"
                usage
                exit 1
                ;;
        esac
    done

    # Apply CLI token override if provided
    if [ -n "$CLI_TOKEN" ]; then
        AXWAY_TOKEN="$CLI_TOKEN"
    fi

    case "$ACTION" in
        file)
            test_from_file "$FILE_PATH"
            ;;
        *)
            main
            ;;
    esac
fi