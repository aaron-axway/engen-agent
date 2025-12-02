#!/bin/bash

# ServiceNow OAuth JWT Test Script
# Tests JWT token generation and OAuth token exchange

set -e

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

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# Load configuration from .env file
if [[ ! -f ".env" ]]; then
    print_error ".env file not found"
    echo "Create a .env file with the required ServiceNow configuration"
    exit 1
fi

# Source the .env file
set -a  # automatically export all variables
source .env
set +a

# Configuration from .env file
SERVICENOW_INSTANCE_URL="${SERVICENOW_INSTANCE_URL}"
CLIENT_ID="${SERVICENOW_OAUTH_CLIENT_ID}"
CLIENT_SECRET="${SERVICENOW_OAUTH_CLIENT_SECRET:-}"
USERNAME="${SERVICENOW_OAUTH_USERNAME}"
KEY_ID="${SERVICENOW_OAUTH_KEY_ID}"

# Validate required configuration
if [[ -z "$SERVICENOW_INSTANCE_URL" ]]; then
    print_error "SERVICENOW_INSTANCE_URL not set in .env file"
    exit 1
fi

if [[ -z "$CLIENT_ID" ]]; then
    print_error "SERVICENOW_OAUTH_CLIENT_ID not set in .env file"
    exit 1
fi

if [[ -z "$USERNAME" ]]; then
    print_error "SERVICENOW_OAUTH_USERNAME not set in .env file"
    exit 1
fi

if [[ -z "$KEY_ID" ]]; then
    print_error "SERVICENOW_OAUTH_KEY_ID not set in .env file"
    exit 1
fi

# Read the base64 private key
if [[ ! -f "./certs/private_key_base64.txt" ]]; then
    print_error "Private key file not found: ./certs/private_key_base64.txt"
    echo "Run ./scripts/generate-servicenow-jwt-keys.sh first"
    exit 1
fi

PRIVATE_KEY_BASE64=$(cat ./certs/private_key_base64.txt)

print_header "ServiceNow OAuth JWT Test"
echo -e "${BLUE}Instance:${NC} $SERVICENOW_INSTANCE_URL"
echo -e "${BLUE}Client ID:${NC} $CLIENT_ID"
echo -e "${BLUE}Username:${NC} $USERNAME"
echo -e "${BLUE}Key ID:${NC} $KEY_ID"

print_header "Step 1: Generate JWT Assertion"

# Create JWT header
JWT_HEADER='{"alg":"RS256","typ":"JWT","kid":"'$KEY_ID'"}'
JWT_HEADER_B64=$(echo -n "$JWT_HEADER" | base64 | tr -d '=' | tr '/+' '_-' | tr -d '\n')

# Create JWT payload
CURRENT_TIME=$(date +%s)
EXPIRY_TIME=$((CURRENT_TIME + 300))  # 5 minutes
JTI=$(uuidgen | tr '[:upper:]' '[:lower:]')
TOKEN_ENDPOINT="$SERVICENOW_INSTANCE_URL/oauth_token.do"

JWT_PAYLOAD='{
  "iss":"'$CLIENT_ID'",
  "sub":"'$USERNAME'", 
  "aud":"'$CLIENT_ID'",
  "jti":"'$JTI'",
  "iat":'$CURRENT_TIME',
  "exp":'$EXPIRY_TIME'
}'

JWT_PAYLOAD_B64=$(echo -n "$JWT_PAYLOAD" | base64 | tr -d '=' | tr '/+' '_-' | tr -d '\n')

# Create signature input
SIGNATURE_INPUT="$JWT_HEADER_B64.$JWT_PAYLOAD_B64"

print_info "JWT Header: $JWT_HEADER"
print_info "JWT Payload: $JWT_PAYLOAD"
print_info "Token Endpoint: $TOKEN_ENDPOINT"

# Decode private key and create signature
echo "$PRIVATE_KEY_BASE64" | base64 -d > /tmp/temp_private_key.pem

# Sign with RSA-SHA256
SIGNATURE=$(echo -n "$SIGNATURE_INPUT" | openssl dgst -sha256 -sign /tmp/temp_private_key.pem | base64 | tr -d '=' | tr '/+' '_-' | tr -d '\n')

# Clean up temp file
rm -f /tmp/temp_private_key.pem

# Create complete JWT
JWT_TOKEN="$SIGNATURE_INPUT.$SIGNATURE"

print_success "JWT token generated successfully"
echo -e "${BLUE}JWT Token:${NC} ${JWT_TOKEN}"

print_header "Step 2: Exchange JWT for Access Token"

# Test OAuth token exchange
print_info "Making OAuth token request to ServiceNow..."

RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" \
  -X POST "$TOKEN_ENDPOINT" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -H "Accept: application/json" \
  -d "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&client_id=$CLIENT_ID&client_secret=$CLIENT_SECRET&assertion=$JWT_TOKEN")

# Extract HTTP status code
HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
RESPONSE_BODY=$(echo "$RESPONSE" | sed '/HTTP_CODE:/d')

echo -e "${BLUE}HTTP Status:${NC} $HTTP_CODE"
echo -e "${BLUE}Response:${NC} $RESPONSE_BODY"

if [[ "$HTTP_CODE" == "200" ]]; then
    print_success "OAuth token exchange successful!"
    
    # Parse access token if available
    ACCESS_TOKEN=$(echo "$RESPONSE_BODY" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)
    if [[ -n "$ACCESS_TOKEN" ]]; then
        print_success "Access token received: ${ACCESS_TOKEN:0:20}..."
        
        print_header "Step 3: Test API Call with Access Token"
        
        # Test a simple API call
        API_RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" \
          -X GET "$SERVICENOW_INSTANCE_URL/api/now/table/sys_user?sysparm_limit=1" \
          -H "Authorization: Bearer $ACCESS_TOKEN" \
          -H "Accept: application/json")
        
        API_HTTP_CODE=$(echo "$API_RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
        API_RESPONSE_BODY=$(echo "$API_RESPONSE" | sed '/HTTP_CODE:/d')
        
        echo -e "${BLUE}API HTTP Status:${NC} $API_HTTP_CODE"
        
        if [[ "$API_HTTP_CODE" == "200" ]]; then
            print_success "API call successful! OAuth JWT flow is working correctly."
            echo -e "${BLUE}API Response:${NC} ${API_RESPONSE_BODY:0:100}..."
        else
            print_error "API call failed with status $API_HTTP_CODE"
            echo -e "${BLUE}API Response:${NC} $API_RESPONSE_BODY"
        fi
    else
        print_error "No access token found in response"
    fi
    
elif [[ "$HTTP_CODE" == "400" ]]; then
    print_error "Bad request - Check JWT format or ServiceNow configuration"
    echo "Common issues:"
    echo "- JWT Verifier Map not configured correctly"
    echo "- Certificate not uploaded to sys_certificate table"
    echo "- Client ID mismatch"
    echo "- Key ID (kid) mismatch"
    
elif [[ "$HTTP_CODE" == "401" ]]; then
    print_error "Unauthorized - JWT signature verification failed"
    echo "Common issues:"
    echo "- Certificate in ServiceNow doesn't match private key"
    echo "- JWT Verifier Map algorithm not set to RS256"
    echo "- Key ID (kid) doesn't match JWT Verifier Map"
    
elif [[ "$HTTP_CODE" == "404" ]]; then
    print_error "Not found - Check ServiceNow instance URL"
    echo "Instance URL: $SERVICENOW_INSTANCE_URL"
    
else
    print_error "OAuth token exchange failed with HTTP status: $HTTP_CODE"
    echo "Response: $RESPONSE_BODY"
fi

print_header "Test Configuration Summary"
echo -e "${BLUE}✓ Instance URL:${NC} $SERVICENOW_INSTANCE_URL"
echo -e "${BLUE}✓ Client ID:${NC} $CLIENT_ID"
echo -e "${BLUE}✓ Username:${NC} $USERNAME"
echo -e "${BLUE}✓ Key ID:${NC} $KEY_ID"
echo -e "${BLUE}✓ JWT Algorithm:${NC} RS256"
echo -e "${BLUE}✓ Private Key:${NC} Available"

print_header "Next Steps"
if [[ "$HTTP_CODE" == "200" ]]; then
    echo -e "${GREEN}🎉 OAuth JWT configuration is working correctly!${NC}"
    echo "Your webhook service can now authenticate with ServiceNow using OAuth JWT."
else
    echo -e "${YELLOW}🔧 Configuration needs adjustment:${NC}"
    echo "1. Verify certificate was uploaded to ServiceNow sys_certificate table"
    echo "2. Check JWT Verifier Map configuration"
    echo "3. Confirm OAuth Application Registry settings"
    echo "4. Ensure integration user exists and has proper roles"
fi