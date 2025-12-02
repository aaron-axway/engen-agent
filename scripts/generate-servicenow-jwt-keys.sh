#!/bin/bash

# ServiceNow OAuth JWT Key Generation Script (CORRECT METHOD)
# This script generates RSA keys and certificate for ServiceNow sys_certificate table
# Based on actual ServiceNow JWT Bearer documentation

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

print_warning() {
    echo -e "${YELLOW}⚠ $1${NC}"
}

print_info() {
    echo -e "${BLUE}ℹ $1${NC}"
}

# Configuration
CERT_DIR="./certs"
PRIVATE_KEY_FILE="$CERT_DIR/servicenow_private_key.pem"
PUBLIC_KEY_FILE="$CERT_DIR/servicenow_public_key.pem"
CERTIFICATE_FILE="$CERT_DIR/servicenow_certificate.pem"
PRIVATE_KEY_BASE64_FILE="$CERT_DIR/private_key_base64.txt"

# Certificate details
CERT_SUBJECT="/C=US/ST=CA/L=San Francisco/O=Webhook Service/OU=Integration/CN=servicenow-jwt/emailAddress=integration@company.com"
CERT_VALIDITY_DAYS=730  # 2 years

# JWT Configuration
KEY_ID="webhook-service-key-001"
CLIENT_ID="webhook-service-client"
USERNAME="jwt.integration.user"

print_header "ServiceNow JWT OAuth Key Generator (CORRECT METHOD)"
echo -e "${BLUE}Generates RSA keys and certificate for ServiceNow sys_certificate table${NC}"

# Create certs directory if it doesn't exist
mkdir -p "$CERT_DIR"

# Check for existing files
if [[ -f "$PRIVATE_KEY_FILE" ]]; then
    print_warning "Existing private key found: $PRIVATE_KEY_FILE"
    read -p "Do you want to overwrite existing keys? (y/N): " -n 1 -r
    echo
    if [[ ! $REPLY =~ ^[Yy]$ ]]; then
        echo "Using existing keys..."
    else
        print_info "Removing existing key files..."
        rm -f "$PRIVATE_KEY_FILE" "$PUBLIC_KEY_FILE" "$CERTIFICATE_FILE" "$PRIVATE_KEY_BASE64_FILE"
    fi
fi

print_header "Step 1: Generate RSA Key Pair"

if [[ ! -f "$PRIVATE_KEY_FILE" ]]; then
    # Generate RSA private key (2048-bit)
    print_info "Generating RSA private key (2048-bit)..."
    openssl genrsa -out "$PRIVATE_KEY_FILE" 2048
    print_success "Generated private key: $PRIVATE_KEY_FILE"
    
    # Set secure permissions
    chmod 600 "$PRIVATE_KEY_FILE"
    print_success "Set secure permissions on private key"
else
    print_success "Using existing private key: $PRIVATE_KEY_FILE"
fi

# Generate public key from private key
if [[ ! -f "$PUBLIC_KEY_FILE" ]]; then
    print_info "Extracting public key from private key..."
    openssl rsa -in "$PRIVATE_KEY_FILE" -pubout -out "$PUBLIC_KEY_FILE"
    print_success "Generated public key: $PUBLIC_KEY_FILE"
else
    print_success "Using existing public key: $PUBLIC_KEY_FILE"
fi

print_header "Step 2: Generate X.509 Certificate"

if [[ ! -f "$CERTIFICATE_FILE" ]]; then
    # Generate self-signed X.509 certificate
    print_info "Generating self-signed X.509 certificate..."
    openssl req -new -x509 \
        -key "$PRIVATE_KEY_FILE" \
        -out "$CERTIFICATE_FILE" \
        -days $CERT_VALIDITY_DAYS \
        -subj "$CERT_SUBJECT"
    
    print_success "Generated certificate: $CERTIFICATE_FILE"
else
    print_success "Using existing certificate: $CERTIFICATE_FILE"
fi

print_header "Step 3: Generate Base64 Encoded Private Key"

# Convert private key to base64 for environment variable
print_info "Converting private key to base64..."
base64 -i "$PRIVATE_KEY_FILE" | tr -d '\n' > "$PRIVATE_KEY_BASE64_FILE"
print_success "Generated base64 private key: $PRIVATE_KEY_BASE64_FILE"

print_header "Step 4: Verify Generated Files"

# Verify private key
if openssl rsa -in "$PRIVATE_KEY_FILE" -check -noout 2>/dev/null; then
    print_success "Private key is valid"
else
    print_error "Private key validation failed"
    exit 1
fi

# Verify certificate
if openssl x509 -in "$CERTIFICATE_FILE" -text -noout > /dev/null 2>&1; then
    print_success "Certificate is valid"
else
    print_error "Certificate validation failed"
    exit 1
fi

# Verify key pair match
PRIVATE_KEY_MODULUS=$(openssl rsa -in "$PRIVATE_KEY_FILE" -noout -modulus)
CERT_MODULUS=$(openssl x509 -in "$CERTIFICATE_FILE" -noout -modulus)

if [[ "$PRIVATE_KEY_MODULUS" == "$CERT_MODULUS" ]]; then
    print_success "Private key and certificate match"
else
    print_error "Private key and certificate do not match"
    exit 1
fi

print_header "Generated Files Summary"
echo -e "${GREEN}Key Files:${NC}"
echo "  🔐 Private Key: $PRIVATE_KEY_FILE"
echo "  🔑 Public Key: $PUBLIC_KEY_FILE"
echo "  📄 Certificate: $CERTIFICATE_FILE"
echo "  📋 Base64 Private Key: $PRIVATE_KEY_BASE64_FILE"
echo ""

# Display certificate information
print_info "Certificate Details:"
echo -e "${BLUE}Subject:${NC} $(openssl x509 -in "$CERTIFICATE_FILE" -subject -noout | sed 's/subject=//')"
echo -e "${BLUE}Validity:${NC}"
openssl x509 -in "$CERTIFICATE_FILE" -dates -noout | sed 's/^/  /'
echo -e "${BLUE}SHA256 Fingerprint:${NC} $(openssl x509 -in "$CERTIFICATE_FILE" -fingerprint -sha256 -noout | cut -d= -f2)"

print_header "ServiceNow Configuration Instructions"

echo -e "${YELLOW}🔹 Step 1: Upload Certificate to ServiceNow sys_certificate Table${NC}"
echo "  1. Login to ServiceNow as admin"
echo "  2. Navigate to: System Security → Certificates → X.509 Certificates"
echo "  3. Click 'New'"
echo "  4. Fill in the form:"
echo "     - Name: Webhook Service JWT Public Key"
echo "     - Format: PEM"
echo "     - Type: Trust Store Cert"
echo "     - PEM Certificate: [Copy ENTIRE content from file below]"
echo ""
echo -e "${GREEN}Certificate file to copy: $CERTIFICATE_FILE${NC}"
echo ""

echo -e "${YELLOW}🔹 Step 2: Create JWT Verifier Map${NC}"
echo "  1. Navigate to: System OAuth → JWT Verifier Maps"
echo "  2. Click 'New'"
echo "  3. Configure:"
echo "     - Name: Webhook Service JWT Verifier"
echo "     - Certificate: [Select certificate from Step 1]"
echo "     - Key ID (kid): $KEY_ID"
echo "     - Algorithm: RS256"
echo "  4. Save the record"
echo ""

echo -e "${YELLOW}🔹 Step 3: Create OAuth Application Registry${NC}"
echo "  1. Navigate to: System OAuth → Application Registry"
echo "  2. Click 'New' → 'Create an OAuth JWT API endpoint for external clients'"
echo "  3. Configure:"
echo "     - Name: Webhook Service JWT Integration"
echo "     - Client ID: $CLIENT_ID"
echo "     - JWT Verifier Map: [Select from Step 2]"
echo "     - User Field: user_name"
echo "     - Enable JTI Verification: true"
echo "  4. Save the record"
echo ""

echo -e "${YELLOW}🔹 Step 4: Create Integration User${NC}"
echo "  1. Navigate to: User Administration → Users"
echo "  2. Click 'New'"
echo "  3. Configure:"
echo "     - User ID: $USERNAME"
echo "     - First name: JWT"
echo "     - Last name: Integration"
echo "     - Email: jwt-integration@yourcompany.com"
echo "     - Active: true"
echo "  4. Assign roles: web_service_admin, itil"
echo ""

print_header "Environment Variables"
echo -e "${YELLOW}Add these to your .env file:${NC}"
echo ""
echo "# ServiceNow OAuth JWT Configuration"
echo "SERVICENOW_OAUTH_ENABLED=true"
echo "SERVICENOW_OAUTH_CLIENT_ID=$CLIENT_ID"
echo "SERVICENOW_OAUTH_USERNAME=$USERNAME"
echo "SERVICENOW_OAUTH_KEY_ID=$KEY_ID"
echo "SERVICENOW_OAUTH_PRIVATE_KEY_BASE64=$(cat "$PRIVATE_KEY_BASE64_FILE")"
echo ""
echo "# ServiceNow instance (update with your instance)"
echo "SERVICENOW_INSTANCE_URL=https://your-instance.service-now.com"
echo "SERVICENOW_API_ENABLED=true"

print_header "Testing Commands"
echo -e "${GREEN}Test certificate content:${NC}"
echo "cat $CERTIFICATE_FILE"
echo ""
echo -e "${GREEN}Verify certificate details:${NC}"
echo "openssl x509 -in $CERTIFICATE_FILE -text -noout"
echo ""
echo -e "${GREEN}Test private key:${NC}"
echo "openssl rsa -in $PRIVATE_KEY_FILE -check"

print_header "Important Security Notes"
echo -e "${RED}🔒 SECURITY REMINDERS:${NC}"
echo "• Keep $PRIVATE_KEY_FILE secure and private"
echo "• Never commit private keys to version control"
echo "• Only share the certificate file ($CERTIFICATE_FILE) with ServiceNow"
echo "• Certificate is valid for $CERT_VALIDITY_DAYS days"
echo "• Rotate keys before expiration"
echo "• Monitor ServiceNow OAuth logs for authentication issues"

print_header "Quick Copy Commands"
echo -e "${BLUE}Copy certificate content for ServiceNow:${NC}"
echo "cat $CERTIFICATE_FILE | pbcopy  # macOS"
echo "cat $CERTIFICATE_FILE | xclip -selection clipboard  # Linux"
echo ""
echo -e "${BLUE}Copy base64 private key for .env:${NC}"
echo "cat $PRIVATE_KEY_BASE64_FILE | pbcopy  # macOS"
echo "cat $PRIVATE_KEY_BASE64_FILE | xclip -selection clipboard  # Linux"

print_success "ServiceNow JWT key generation completed successfully!"
echo ""
echo -e "${GREEN}Next steps:${NC}"
echo "1. Copy certificate content from: $CERTIFICATE_FILE"
echo "2. Upload to ServiceNow sys_certificate table"
echo "3. Follow the configuration instructions above"
echo "4. Update your .env file with the provided values"
echo "5. Test your OAuth integration"
echo ""
echo -e "${BLUE}Configuration Summary:${NC}"
echo -e "${GREEN}Key ID:${NC} $KEY_ID"
echo -e "${GREEN}Client ID:${NC} $CLIENT_ID"
echo -e "${GREEN}Username:${NC} $USERNAME"
echo -e "${GREEN}Algorithm:${NC} RS256"