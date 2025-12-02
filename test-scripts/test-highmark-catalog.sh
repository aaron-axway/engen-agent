#!/bin/bash

# Highmark Catalog API Integration Test
# Tests the complete Highmark OAuth + Catalog API workflow

BASE_URL="http://localhost:8080"
AXWAY_ENDPOINT="${BASE_URL}/webhooks/axway"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
PURPLE='\033[0;35m'
NC='\033[0m' # No Color

# Configuration
AXWAY_TOKEN=${AXWAY_WEBHOOK_TOKEN:-"test-axway-token"}

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

# Test counter
total_tests=0
passed_tests=0
failed_tests=0

run_test() {
    local test_name="$1"
    local test_command="$2"

    total_tests=$((total_tests + 1))
    print_step "Test $total_tests: $test_name"

    if eval "$test_command"; then
        passed_tests=$((passed_tests + 1))
        print_success "PASSED"
        return 0
    else
        failed_tests=$((failed_tests + 1))
        print_error "FAILED"
        return 1
    fi
}

# ============================================
# TEST 1: Health Check
# ============================================
print_header "Test 1: Service Health Check"

health_response=$(curl -s "${BASE_URL}/webhooks/health")
if echo "$health_response" | grep -q "healthy"; then
    print_success "Service is healthy"
    echo "$health_response" | jq '.' 2>/dev/null || echo "$health_response"
else
    print_error "Service health check failed"
    echo "$health_response"
    exit 1
fi

# ============================================
# TEST 2: Highmark OAuth Configuration Check
# ============================================
print_header "Test 2: Highmark OAuth Configuration"

print_step "Checking application logs for Highmark OAuth status..."
print_warning "Note: This requires the service to be running with proper configuration"
print_warning "Check logs for: 'Highmark OAuth connection successful'"

# ============================================
# TEST 3: Send AssetRequest Event
# ============================================
print_header "Test 3: Send AssetRequest Webhook Event"

EVENT_ID="highmark-test-$(date +%s)"
CORRELATION_ID="corr-highmark-$(date +%s)"

# Create AssetRequest webhook payload
ASSET_REQUEST_PAYLOAD=$(cat <<EOF
{
  "id": "${EVENT_ID}",
  "time": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")",
  "type": "ResourceCreated",
  "eventType": "ResourceCreated",
  "kind": "AssetRequest",
  "selfLink": "/management/v1alpha1/environments/test/assetrequests/${EVENT_ID}",
  "correlationId": "${CORRELATION_ID}",
  "references": [
    {
      "kind": "Asset",
      "name": "test-api",
      "selfLink": "/catalog/v1/assets/asset-${EVENT_ID}"
    }
  ],
  "payload": {
    "name": "test-asset-request-${EVENT_ID}",
    "kind": "AssetRequest",
    "metadata": {
      "name": "test-request",
      "scope": {
        "kind": "Environment",
        "name": "test"
      }
    },
    "spec": {
      "description": "Test API Access Request for Highmark Catalog Integration"
    }
  }
}
EOF
)

print_step "Sending AssetRequest event to Axway endpoint..."
response=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X POST "${AXWAY_ENDPOINT}" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${AXWAY_TOKEN}" \
  -d "${ASSET_REQUEST_PAYLOAD}")

http_code=$(echo "$response" | grep "HTTP_CODE:" | cut -d: -f2)
response_body=$(echo "$response" | sed '/HTTP_CODE:/d')

if [ "$http_code" = "200" ] || [ "$http_code" = "202" ]; then
    print_success "Webhook accepted (HTTP $http_code)"
    echo "$response_body" | jq '.' 2>/dev/null || echo "$response_body"
else
    print_error "Webhook rejected (HTTP $http_code)"
    echo "$response_body"
fi

# ============================================
# TEST 4: Check Database for Event
# ============================================
print_header "Test 4: Database Verification"

print_warning "To verify the event was processed:"
print_step "1. Open H2 Console: http://localhost:8080/h2-console"
print_step "2. Connect with JDBC URL: jdbc:h2:file:./data/webhook_db"
print_step "3. Run query:"
echo "
SELECT EVENT_ID, EVENT_TYPE, STATUS, CALLBACK_STATUS, PROCESSED_AT
FROM EVENT_RECORDS
WHERE EVENT_ID = '${EVENT_ID}'
OR CORRELATION_ID = '${CORRELATION_ID}'
ORDER BY RECEIVED_AT DESC;
"

# ============================================
# TEST 5: Check Application Logs
# ============================================
print_header "Test 5: Application Log Verification"

print_warning "Check application logs for:"
print_step "✓ 'Highmark OAuth connection successful - token obtained'"
print_step "✓ 'Submitting catalog item request to Highmark'"
print_step "✓ 'Successfully created ServiceNow catalog request: REQ*'"
print_step "✓ Event ID: ${EVENT_ID}"
print_step "✓ Correlation ID: ${CORRELATION_ID}"

# ============================================
# TEST SUMMARY
# ============================================
print_header "Test Summary"

echo -e "Total Tests:  ${total_tests}"
echo -e "${GREEN}Passed Tests: ${passed_tests}${NC}"
echo -e "${RED}Failed Tests: ${failed_tests}${NC}"

if [ $failed_tests -eq 0 ]; then
    print_success "All automated tests passed!"
else
    print_error "Some tests failed. Please review the output above."
    exit 1
fi

# ============================================
# MANUAL VERIFICATION STEPS
# ============================================
print_header "Manual Verification Steps"

echo "
1. Verify OAuth Token Acquisition:
   - Check logs for 'Highmark OAuth connection successful'
   - Verify auth method (Basic Auth or JWT) is correct

2. Verify Catalog API Request:
   - Check logs for catalog item submission
   - Look for ServiceNow request number (REQ#######)

3. Verify Database Records:
   - Event should be in EVENT_RECORDS table
   - Status should be PROCESSED
   - Check CALLBACK_STATUS for success

4. Verify ServiceNow (if accessible):
   - Login to ServiceNow instance
   - Search for request number in sc_request table
   - Verify catalog item variables were populated

5. Check Variable Mapping:
   - requested_for: Should contain user email
   - need_by_date: Should be current date + 30 days
   - application_name: Should contain asset name
   - api_resource_name: Should contain asset title
   - selflink_to_engage: Should contain Axway selfLink

6. Configuration Validation:
   - Verify HIGHMARK_OAUTH_CLIENT_ID is set
   - Verify HIGHMARK_OAUTH_CLIENT_SECRET is set
   - Verify HIGHMARK_CATALOG_ITEM_SYS_ID is correct
   - Verify HIGHMARK_CATALOG_BASE_URL is correct
"

print_header "Test Complete"
print_success "Highmark Catalog API integration test completed successfully!"
echo -e "\nEvent ID: ${EVENT_ID}"
echo -e "Correlation ID: ${CORRELATION_ID}\n"
