#!/bin/bash

# Test script for Axway native webhook payload format
# Based on official Axway documentation

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

# Function to send test webhook with actual Axway format
send_axway_native_event() {
    local event_type="$1"
    local description="$2"
    
    print_header "Testing Axway Native Format: $description"
    
    # Generate unique IDs
    EVENT_ID=$(uuidgen 2>/dev/null || echo "test-$(date +%s)-$$")
    CORRELATION_ID=$(uuidgen 2>/dev/null || echo "corr-$(date +%s)-$$")
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3N+0000")
    
    # Prepare payload based on event type
    case "$event_type" in
        "SubscriptionUpdatedEvent")
            PAYLOAD=$(cat <<EOF
{
  "id": "$EVENT_ID",
  "time": "$TIMESTAMP",
  "version": "v1",
  "product": "AmplifyCentral",
  "correlationId": "$CORRELATION_ID",
  "organization": {
    "id": "100000142"
  },
  "type": "SubscriptionUpdatedEvent",
  "payload": {
    "consumerInstance": {
      "kind": "ConsumerInstance",
      "name": "consumer-$(date +%s)",
      "tags": ["APIID_94963a55-bacd-49d1-8c03-d4262000611f"],
      "group": "management",
      "metadata": {
        "id": "e4fda7a6746fbcdb01748e763cbb2979",
        "audit": {
          "createUserId": "DOSA_97c389b884314886a57467bcf0487e27",
          "createTimestamp": "$TIMESTAMP",
          "modifyTimestamp": "$TIMESTAMP"
        },
        "scope": {
          "id": "e4eca6cb72a28c140172b38f231e071f",
          "kind": "Environment",
          "name": "production",
          "selfLink": "/management/v1alpha1/environments/production"
        }
      }
    },
    "subscription": {
      "id": "sub-$(date +%s)",
      "name": "API Subscription",
      "state": "APPROVED",
      "nextPossibleStates": ["ACTIVE", "SUSPENDED"]
    }
  }
}
EOF
)
            ;;
            
        "APIServiceCreatedEvent")
            PAYLOAD=$(cat <<EOF
{
  "id": "$EVENT_ID",
  "time": "$TIMESTAMP",
  "version": "v1",
  "product": "AmplifyCentral",
  "correlationId": "$CORRELATION_ID",
  "organization": {
    "id": "100000142"
  },
  "type": "APIServiceCreatedEvent",
  "payload": {
    "apiService": {
      "kind": "APIService",
      "name": "payment-api-v2",
      "title": "Payment Processing API",
      "group": "management",
      "apiVersion": "v1alpha1",
      "metadata": {
        "id": "api-$(date +%s)",
        "audit": {
          "createUserId": "user_$(date +%s)",
          "createTimestamp": "$TIMESTAMP"
        },
        "scope": {
          "id": "env-prod-001",
          "kind": "Environment",
          "name": "production"
        }
      },
      "spec": {
        "description": "Payment processing service API"
      }
    }
  }
}
EOF
)
            ;;
            
        "APIServiceUpdatedEvent")
            PAYLOAD=$(cat <<EOF
{
  "id": "$EVENT_ID",
  "time": "$TIMESTAMP",
  "version": "v1",
  "product": "AmplifyCentral",
  "correlationId": "$CORRELATION_ID",
  "organization": {
    "id": "100000142"
  },
  "type": "APIServiceUpdatedEvent",
  "payload": {
    "apiService": {
      "kind": "APIService",
      "name": "payment-api-v2",
      "title": "Payment Processing API - Updated",
      "group": "management",
      "apiVersion": "v1alpha1",
      "metadata": {
        "id": "api-$(date +%s)",
        "audit": {
          "createUserId": "user_$(date +%s)",
          "createTimestamp": "$TIMESTAMP",
          "modifyTimestamp": "$TIMESTAMP"
        },
        "scope": {
          "id": "env-prod-001",
          "kind": "Environment",
          "name": "production"
        }
      },
      "spec": {
        "description": "Payment processing service API with enhanced features"
      }
    }
  }
}
EOF
)
            ;;
            
        "APIServiceInstanceCreatedEvent")
            PAYLOAD=$(cat <<EOF
{
  "id": "$EVENT_ID",
  "time": "$TIMESTAMP",
  "version": "v1",
  "product": "AmplifyCentral",
  "correlationId": "$CORRELATION_ID",
  "organization": {
    "id": "100000142"
  },
  "type": "APIServiceInstanceCreatedEvent",
  "payload": {
    "apiServiceInstance": {
      "kind": "APIServiceInstance",
      "name": "payment-api-instance-prod",
      "group": "management",
      "apiVersion": "v1alpha1",
      "metadata": {
        "id": "instance-$(date +%s)",
        "audit": {
          "createUserId": "user_$(date +%s)",
          "createTimestamp": "$TIMESTAMP"
        },
        "scope": {
          "id": "env-prod-001",
          "kind": "Environment",
          "name": "production"
        }
      },
      "spec": {
        "endpoint": [
          {
            "host": "api.example.com",
            "port": 443,
            "protocol": "https"
          }
        ]
      }
    }
  }
}
EOF
)
            ;;
            
        *)
            print_error "Unknown event type: $event_type"
            return 1
            ;;
    esac
    
    # Send the request
    print_info "Sending $event_type to $BASE_URL/webhooks/axway"
    
    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/webhooks/axway" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -H "X-Axway-Event-Id: $EVENT_ID" \
        -H "User-Agent: amplify-central" \
        -d "$PAYLOAD" 2>/dev/null)
    
    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
    BODY=$(echo "$RESPONSE" | head -n-1)
    
    if [ "$HTTP_CODE" -eq 200 ] || [ "$HTTP_CODE" -eq 201 ]; then
        print_success "Event sent successfully (HTTP $HTTP_CODE)"
        if [ -n "$BODY" ]; then
            echo "Response: $BODY" | jq . 2>/dev/null || echo "Response: $BODY"
        fi
    else
        print_error "Failed to send event (HTTP $HTTP_CODE)"
        if [ -n "$BODY" ]; then
            echo "Error response: $BODY" | jq . 2>/dev/null || echo "Error response: $BODY"
        fi
        return 1
    fi
    
    echo ""
}

# Function to test all event types
test_all_events() {
    print_header "Testing All Axway Native Event Formats"
    
    local success_count=0
    local fail_count=0
    
    # Test API Service events
    if send_axway_native_event "APIServiceCreatedEvent" "API Service Creation"; then
        ((success_count++))
    else
        ((fail_count++))
    fi
    
    sleep 1
    
    if send_axway_native_event "APIServiceUpdatedEvent" "API Service Update"; then
        ((success_count++))
    else
        ((fail_count++))
    fi
    
    sleep 1
    
    # Test API Service Instance events
    if send_axway_native_event "APIServiceInstanceCreatedEvent" "API Service Instance Creation"; then
        ((success_count++))
    else
        ((fail_count++))
    fi
    
    sleep 1
    
    # Test Subscription events
    if send_axway_native_event "SubscriptionUpdatedEvent" "Subscription Update"; then
        ((success_count++))
    else
        ((fail_count++))
    fi
    
    # Summary
    print_header "Test Summary"
    print_success "Successful events: $success_count"
    if [ $fail_count -gt 0 ]; then
        print_error "Failed events: $fail_count"
    fi
    
    return $fail_count
}

# Function to test backward compatibility with generic format
test_backward_compatibility() {
    print_header "Testing Backward Compatibility (Generic Format)"
    
    EVENT_ID="generic-$(date +%s)"
    CORRELATION_ID="corr-generic-$(date +%s)"
    
    GENERIC_PAYLOAD=$(cat <<EOF
{
  "id": "$EVENT_ID",
  "eventType": "api.created",
  "correlationId": "$CORRELATION_ID",
  "source": "test-script",
  "payload": {
    "apiName": "test-api",
    "version": "1.0"
  }
}
EOF
)
    
    print_info "Sending generic format event to Axway endpoint"
    
    RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/webhooks/axway" \
        -H "Content-Type: application/json" \
        -H "Authorization: Bearer $AXWAY_TOKEN" \
        -d "$GENERIC_PAYLOAD" 2>/dev/null)
    
    HTTP_CODE=$(echo "$RESPONSE" | tail -n1)
    BODY=$(echo "$RESPONSE" | head -n-1)
    
    if [ "$HTTP_CODE" -eq 200 ] || [ "$HTTP_CODE" -eq 201 ]; then
        print_success "Generic format still supported (HTTP $HTTP_CODE)"
        return 0
    else
        print_error "Generic format failed (HTTP $HTTP_CODE)"
        return 1
    fi
}

# Main menu
show_menu() {
    echo ""
    print_header "Axway Native Format Test Menu"
    echo "1) Test Subscription Update Event"
    echo "2) Test API Service Creation Event"
    echo "3) Test API Service Update Event"
    echo "4) Test API Service Instance Creation Event"
    echo "5) Test All Events"
    echo "6) Test Backward Compatibility"
    echo "7) Exit"
    echo ""
}

# Main execution
main() {
    print_header "Axway Native Format Webhook Tester"
    print_info "Base URL: $BASE_URL"
    print_info "Using token: ${AXWAY_TOKEN:0:10}..."
    
    if [ "$1" = "all" ]; then
        test_all_events
        test_backward_compatibility
        exit $?
    fi
    
    while true; do
        show_menu
        read -p "Select option: " choice
        
        case $choice in
            1) send_axway_native_event "SubscriptionUpdatedEvent" "Subscription Update" ;;
            2) send_axway_native_event "APIServiceCreatedEvent" "API Service Creation" ;;
            3) send_axway_native_event "APIServiceUpdatedEvent" "API Service Update" ;;
            4) send_axway_native_event "APIServiceInstanceCreatedEvent" "API Service Instance Creation" ;;
            5) test_all_events ;;
            6) test_backward_compatibility ;;
            7) print_info "Exiting..."; exit 0 ;;
            *) print_error "Invalid option" ;;
        esac
    done
}

# Handle command line arguments
case "$1" in
    "all")
        main "all"
        ;;
    "help"|"-h"|"--help")
        echo "Usage: $0 [all]"
        echo ""
        echo "Test Axway webhook with native payload format"
        echo ""
        echo "Options:"
        echo "  all     Run all tests automatically"
        echo "  help    Show this help message"
        echo ""
        echo "Environment variables:"
        echo "  BASE_URL              Webhook service URL (default: http://localhost:8080)"
        echo "  AXWAY_WEBHOOK_TOKEN   Authentication token"
        ;;
    *)
        main
        ;;
esac