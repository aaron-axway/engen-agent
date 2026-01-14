# ServiceNow Webhook Authentication & Authorization

This document describes how authentication and authorization work for webhooks coming from ServiceNow to the Webhook Service.

---

## Flow Diagram

```
ServiceNow                          Webhook Service
    │                                     │
    │  POST /webhooks/servicenow          │
    │  Authorization: Basic <base64>      │
    │  (or X-ServiceNow-Signature: hmac)  │
    │────────────────────────────────────>│
    │                                     │
    │                          ┌──────────┴──────────┐
    │                          │  SecurityConfig     │
    │                          │  (/webhooks/servicenow │
    │                          │   requires .authenticated()) │
    │                          └──────────┬──────────┘
    │                                     │
    │                          ┌──────────┴──────────┐
    │                          │ WebhookAuthenticationFilter │
    │                          │ - Intercepts /webhooks/*    │
    │                          │ - Detects source="servicenow"│
    │                          │ - Calls AuthenticationService│
    │                          └──────────┬──────────┘
    │                                     │
    │                          ┌──────────┴──────────┐
    │                          │ AuthenticationService │
    │                          │ authenticateServiceNowWebhook() │
    │                          │                      │
    │                          │ Option 1: Basic Auth │
    │                          │   - Decode Base64    │
    │                          │   - Compare user:pass│
    │                          │                      │
    │                          │ Option 2: HMAC-SHA256│
    │                          │   - X-ServiceNow-Signature │
    │                          │   - Compute HMAC of body   │
    │                          │   - Compare signatures     │
    │                          └──────────┬──────────┘
    │                                     │
    │                          ┌──────────┴──────────┐
    │                          │ If authenticated:   │
    │                          │ Set SecurityContext │
    │                          │ → WebhookController │
    │                          │                     │
    │                          │ If NOT authenticated:│
    │                          │ → 403 Forbidden     │
    │                          └─────────────────────┘
```

---

## Components

### 1. SecurityConfig.java (Authorization)

**Location**: `src/main/java/com/engen/webhookservice/config/SecurityConfig.java`

```java
.authorizeHttpRequests(authz -> authz
    .requestMatchers("/webhooks/servicenow").authenticated()  // Requires auth
    .anyRequest().denyAll()
)
.addFilterBefore(webhookAuthenticationFilter, ...)  // Custom filter
```

**Responsibilities**:
- Defines which endpoints require authentication
- Requires authentication for `/webhooks/servicenow`
- Injects custom `WebhookAuthenticationFilter` before Spring's default auth filter
- Configures stateless session management (no server-side sessions)

---

### 2. WebhookAuthenticationFilter.java (Routing)

**Location**: `src/main/java/com/engen/webhookservice/config/WebhookAuthenticationFilter.java`

```java
@Override
protected void doFilterInternal(HttpServletRequest request, ...) {

    // Only process webhook endpoints
    if (!request.getRequestURI().startsWith("/webhooks/")) {
        filterChain.doFilter(request, response);
        return;
    }

    // Determine webhook source from URL
    String source = extractWebhookSource(request.getRequestURI());
    boolean authenticated = false;

    // Route to appropriate authentication method
    if ("servicenow".equals(source)) {
        authenticated = authenticationService.authenticateServiceNowWebhook(request);
    }

    // Set Spring Security context if authenticated
    if (authenticated) {
        UsernamePasswordAuthenticationToken authToken =
            new UsernamePasswordAuthenticationToken(source, null, Collections.emptyList());
        SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    filterChain.doFilter(request, response);
}
```

**Responsibilities**:
- Intercepts all `/webhooks/*` requests
- Extracts webhook source from URL path (`/webhooks/servicenow` → `servicenow`)
- Delegates to `AuthenticationService` for credential validation
- Sets Spring Security context if authentication succeeds
- Allows request to proceed to controller (Spring Security enforces auth requirement)

---

### 3. AuthenticationService.java (Authentication Logic)

**Location**: `src/main/java/com/engen/webhookservice/service/AuthenticationService.java`

The service supports **two authentication methods** for ServiceNow webhooks:

#### Method 1: Basic Authentication

```java
public boolean authenticateServiceNowWebhook(ContentCachingRequestWrapper request) {
    // Check Basic Authentication
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Basic ")) {
        String encodedCredentials = authHeader.substring(6);
        String credentials = new String(Base64.getDecoder().decode(encodedCredentials));
        String[] parts = credentials.split(":");

        if (parts.length == 2 &&
            serviceNowUsername.equals(parts[0]) &&
            serviceNowPassword.equals(parts[1])) {
            log.debug("ServiceNow webhook authenticated via Basic auth");
            return true;
        }
    }
    // ... HMAC check follows
}
```

**How it works**:
1. Extract `Authorization` header from request
2. Verify it starts with `Basic `
3. Base64 decode the credentials portion
4. Split into username and password
5. Compare against configured credentials

**Example Request**:
```http
POST /webhooks/servicenow HTTP/1.1
Host: webhook-service.company.com
Authorization: Basic d2ViaG9vay11c2VyOnNlY3VyZS1wYXNzd29yZA==
Content-Type: application/json

{"event": "change.approved", ...}
```

---

#### Method 2: HMAC-SHA256 Signature

```java
// Check HMAC signature validation for ServiceNow
String signature = request.getHeader("X-ServiceNow-Signature");
if (signature != null && !serviceNowSecret.isEmpty()) {
    try {
        byte[] bodyBytes = request.getContentAsByteArray();
        String body = new String(bodyBytes, StandardCharsets.UTF_8);

        String calculatedSignature = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, serviceNowSecret)
            .hmacHex(body);

        if (calculatedSignature.equals(signature)) {
            log.debug("ServiceNow webhook authenticated via HMAC signature");
            return true;
        }
    } catch (Exception e) {
        log.error("Error validating ServiceNow HMAC signature", e);
    }
}
```

**How it works**:
1. Extract `X-ServiceNow-Signature` header from request
2. Read the complete request body
3. Compute HMAC-SHA256 of the body using shared secret
4. Compare computed signature with provided signature

**Example Request**:
```http
POST /webhooks/servicenow HTTP/1.1
Host: webhook-service.company.com
X-ServiceNow-Signature: 7d38b5cd89f1a2b3c4d5e6f7890abcdef1234567890abcdef1234567890abcdef
Content-Type: application/json

{"event": "change.approved", ...}
```

**Signature Computation** (ServiceNow side):
```javascript
// ServiceNow Business Rule or Script Include
var secret = "your-shared-secret";
var body = JSON.stringify(payload);
var signature = new GlideSysAttachment().hmacSHA256(body, secret);
request.setHeader("X-ServiceNow-Signature", signature);
```

---

## Configuration

### Environment Variables

```bash
# ===========================================
# ServiceNow Webhook Authentication
# ===========================================

# Basic Authentication credentials
SERVICENOW_WEBHOOK_USERNAME=webhook-integration-user
SERVICENOW_WEBHOOK_PASSWORD=secure-password-here

# HMAC secret (alternative to Basic Auth)
SERVICENOW_WEBHOOK_SECRET=your-servicenow-hmac-secret
```

### application.yml

```yaml
webhook:
  servicenow:
    secret: ${SERVICENOW_WEBHOOK_SECRET:}
    username: ${SERVICENOW_WEBHOOK_USERNAME:}
    password: ${SERVICENOW_WEBHOOK_PASSWORD:}
```

---

## Authentication Methods Comparison

| Method | Header | Value Format | Pros | Cons |
|--------|--------|--------------|------|------|
| **Basic Auth** | `Authorization` | `Basic <base64(user:pass)>` | Simple, widely supported, easy to configure | Credentials in every request (use HTTPS!) |
| **HMAC-SHA256** | `X-ServiceNow-Signature` | `<hex-hmac-of-body>` | Tamper-proof, no credentials exposed | More complex to implement |

---

## Authentication Flow Summary

```
┌─────────────────────────────────────────────────────────────┐
│                    REQUEST ARRIVES                          │
│              POST /webhooks/servicenow                      │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              WebhookAuthenticationFilter                    │
│                                                             │
│  1. Check if URL starts with /webhooks/                     │
│  2. Extract source from URL → "servicenow"                  │
│  3. Call authenticationService.authenticateServiceNowWebhook│
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                AuthenticationService                        │
│                                                             │
│  Try Basic Auth:                                            │
│  ├─ Has "Authorization: Basic ..." header?                  │
│  ├─ Decode Base64 → username:password                       │
│  └─ Match against SERVICENOW_WEBHOOK_USERNAME/PASSWORD?     │
│      └─ YES → return true (authenticated)                   │
│                                                             │
│  Try HMAC Signature:                                        │
│  ├─ Has "X-ServiceNow-Signature" header?                    │
│  ├─ Compute HMAC-SHA256(body, SERVICENOW_WEBHOOK_SECRET)    │
│  └─ Signatures match?                                       │
│      └─ YES → return true (authenticated)                   │
│                                                             │
│  Neither worked → return false                              │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│              WebhookAuthenticationFilter                    │
│                                                             │
│  If authenticated:                                          │
│  └─ Set SecurityContext with authentication token           │
│                                                             │
│  Continue filter chain...                                   │
└─────────────────────────┬───────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────────┐
│                    SecurityConfig                           │
│                                                             │
│  Check: Is /webhooks/servicenow authenticated?              │
│  ├─ YES → Allow request to proceed to WebhookController     │
│  └─ NO  → Return 403 Forbidden                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Key Implementation Details

### 1. Either Method Works

Authentication succeeds if **either** Basic Auth **or** HMAC signature is valid. You don't need both:

```java
// Basic Auth check
if (basicAuthValid) return true;

// HMAC check (only if Basic Auth failed)
if (hmacValid) return true;

// Neither worked
return false;
```

### 2. Stateless Authentication

Each request is authenticated independently:
- No server-side sessions
- No cookies required
- Credentials/signature must be sent with every request

```java
.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
```

### 3. Fail-Fast Behavior

If authentication fails:
- Request is **not** blocked by the filter itself
- Filter sets no authentication in SecurityContext
- Spring Security's authorization check rejects the request
- Returns **403 Forbidden** response

### 4. Request Body Preservation

For HMAC validation, the request body must be read and preserved:

```java
ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
// Body can now be read multiple times
byte[] bodyBytes = wrappedRequest.getContentAsByteArray();
```

### 5. Logging

All authentication attempts are logged:

```java
log.debug("ServiceNow webhook authenticated via Basic auth");
log.debug("ServiceNow webhook authenticated via HMAC signature");
log.warn("ServiceNow webhook authentication failed");
```

---

## Testing Authentication

### Test with Basic Auth (curl)

```bash
# Encode credentials
CREDENTIALS=$(echo -n "webhook-user:secure-password" | base64)

# Send request
curl -X POST http://localhost:8080/webhooks/servicenow \
  -H "Authorization: Basic $CREDENTIALS" \
  -H "Content-Type: application/json" \
  -d '{"eventType": "change.approved", "eventId": "test-123"}'
```

### Test with HMAC Signature (curl)

```bash
# Compute HMAC (requires openssl)
BODY='{"eventType": "change.approved", "eventId": "test-123"}'
SECRET="your-servicenow-hmac-secret"
SIGNATURE=$(echo -n "$BODY" | openssl dgst -sha256 -hmac "$SECRET" | sed 's/^.* //')

# Send request
curl -X POST http://localhost:8080/webhooks/servicenow \
  -H "X-ServiceNow-Signature: $SIGNATURE" \
  -H "Content-Type: application/json" \
  -d "$BODY"
```

### Test Authentication Failure

```bash
# No auth headers - should return 403
curl -X POST http://localhost:8080/webhooks/servicenow \
  -H "Content-Type: application/json" \
  -d '{"eventType": "test"}'

# Response: 403 Forbidden
```

---

## Security Recommendations

1. **Always use HTTPS** - Especially with Basic Auth to protect credentials in transit
2. **Rotate credentials regularly** - Update username/password periodically
3. **Use strong secrets** - HMAC secrets should be at least 32 characters
4. **Monitor failed attempts** - Watch logs for authentication failures
5. **Prefer HMAC for production** - More secure than Basic Auth as it validates message integrity

---

## Related Files

| File | Purpose |
|------|---------|
| `SecurityConfig.java` | Spring Security configuration, authorization rules |
| `WebhookAuthenticationFilter.java` | Custom filter routing to auth methods |
| `AuthenticationService.java` | Credential validation logic |
| `WebhookController.java` | Endpoint handler (after authentication) |
| `.env` | Credential configuration |
| `application.yml` | Property bindings |
