#!/bin/bash

# Webhook Service Startup Script
# Includes proper JVM arguments for Java 24 compatibility

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

print_header() {
    echo -e "${BLUE}=== $1 ===${NC}"
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

# Check if Java is available
check_java() {
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed or not in PATH"
        exit 1
    fi
    
    java_version=$(java -version 2>&1 | head -n1 | awk -F '"' '{print $2}')
    print_success "Java version: $java_version"
}

# Always invoke Gradle — its incremental build is smart enough to skip work if
# nothing changed, and catches the "source newer than JAR" case automatically.
build_if_needed() {
    print_header "Building (Gradle will skip if nothing changed)"
    if ! ./gradlew bootJar; then
        print_error "Build failed"
        exit 1
    fi
    print_success "Build ready"
}

# Start the application with proper JVM arguments
start_application() {
    print_header "Starting Webhook Service"
    
    # JVM arguments for Java 24 compatibility
    JVM_ARGS=(
        "--enable-native-access=ALL-UNNAMED"
        "-XX:+UnlockExperimentalVMOptions"
        "-XX:MaxMetaspaceSize=512m"
        "-Dspring.profiles.active=dev"
        "-Dlogging.level.com.engen.webhookservice.service=DEBUG"
        "-Dlogging.level.com.engen.webhookservice.config=DEBUG"
    )
    
    # Optional: Set memory limits
    if [ -n "$WEBHOOK_SERVICE_MEMORY" ]; then
        JVM_ARGS+=("-Xmx$WEBHOOK_SERVICE_MEMORY")
        print_success "Memory limit set to: $WEBHOOK_SERVICE_MEMORY"
    fi
    
    # Optional: Enable remote debugging
    if [ "$WEBHOOK_DEBUG" = "true" ]; then
        JVM_ARGS+=("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005")
        print_success "Debug mode enabled on port 5005"
    fi
    
    # Optional: Enable JVM monitoring
    if [ "$WEBHOOK_MONITORING" = "true" ]; then
        JVM_ARGS+=("-Dcom.sun.management.jmxremote")
        JVM_ARGS+=("-Dcom.sun.management.jmxremote.port=9999")
        JVM_ARGS+=("-Dcom.sun.management.jmxremote.authenticate=false")
        JVM_ARGS+=("-Dcom.sun.management.jmxremote.ssl=false")
        print_success "JMX monitoring enabled on port 9999"
    fi
    
    JAR_FILE="build/libs/webhook-service-0.0.1-SNAPSHOT.jar"
    
    echo "Starting with JVM args: ${JVM_ARGS[*]}"
    echo "JAR file: $JAR_FILE"
    echo ""
    
    # Start the application
    java "${JVM_ARGS[@]}" -jar "$JAR_FILE"
}

# Load environment variables from the selected env file
load_env_file() {
    local env_file="${ENV_FILE:-.env}"
    if [ -f "$env_file" ]; then
        print_success "Loading environment variables from $env_file"
        set -a  # automatically export all variables
        # shellcheck disable=SC1090
        source "$env_file"
        set +a
    else
        print_warning "Env file '$env_file' not found - using system environment variables"
    fi
}

# Main execution
main() {
    print_header "Webhook Service Launcher"
    
    # Environment info
    echo "Working directory: $(pwd)"
    echo "Script location: $(dirname "$0")"
    echo ""
    
    # Load environment variables
    load_env_file
    
    # Check environment
    check_java
    build_if_needed
    
    # Show configuration
    print_header "Configuration"
    echo "Profile: ${SPRING_PROFILES_ACTIVE:-dev}"
    echo "Port: ${SERVER_PORT:-8080}"
    echo "Database: ${DATABASE_URL:-jdbc:h2:file:./data/webhook_db}"
    echo ""
    
    # Show optional settings
    if [ -n "$WEBHOOK_SERVICE_MEMORY" ]; then
        echo "Memory limit: $WEBHOOK_SERVICE_MEMORY"
    fi
    if [ "$WEBHOOK_DEBUG" = "true" ]; then
        echo "Debug mode: enabled (port 5005)"
    fi
    if [ "$WEBHOOK_MONITORING" = "true" ]; then
        echo "JMX monitoring: enabled (port 9999)"
    fi
    echo ""
    
    # Start the service
    start_application
}

# Handle script arguments
ACTION="run"
ENV_FILE=".env"

show_help() {
    echo "Webhook Service Startup Script"
    echo ""
    echo "Usage: $0 [options] [command]"
    echo ""
    echo "Commands:"
    echo "  (none)              Start the service (default)"
    echo "  build               Build the application only"
    echo "  clean               Clean build artifacts"
    echo "  help, -h, --help    Show this help message"
    echo ""
    echo "Options:"
    echo "  -e, --env-file <path>    Load env vars from <path> instead of .env"
    echo ""
    echo "Environment Variables:"
    echo "  ENV_FILE                 Alternative to --env-file (flag takes precedence)"
    echo "  WEBHOOK_SERVICE_MEMORY   Set JVM memory limit (e.g., '512m', '1g')"
    echo "  WEBHOOK_DEBUG=true       Enable remote debugging on port 5005"
    echo "  WEBHOOK_MONITORING=true  Enable JMX monitoring on port 9999"
    echo "  SPRING_PROFILES_ACTIVE   Set Spring profile (default: dev)"
    echo "  SERVER_PORT              Set server port (default: 8080)"
    echo ""
    echo "Examples:"
    echo "  $0                                    # Start with .env"
    echo "  $0 --env-file .env-mock               # Start against the mock server"
    echo "  $0 -e .env-mock                       # Short form"
    echo "  ENV_FILE=.env-mock $0                 # Via environment variable"
    echo "  WEBHOOK_SERVICE_MEMORY=1g $0          # Start with 1GB memory"
    echo "  WEBHOOK_DEBUG=true $0                 # Start with debug enabled"
    echo "  SERVER_PORT=9090 $0                   # Start on port 9090"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -e|--env-file)
            if [ -z "${2:-}" ]; then
                print_error "$1 requires a path argument"
                exit 1
            fi
            ENV_FILE="$2"
            shift 2
            ;;
        help|-h|--help)
            ACTION="help"
            shift
            ;;
        build)
            ACTION="build"
            shift
            ;;
        clean)
            ACTION="clean"
            shift
            ;;
        *)
            print_error "Unknown option: $1"
            echo "Use '$0 help' for usage information"
            exit 1
            ;;
    esac
done

case "$ACTION" in
    help)
        show_help
        ;;
    build)
        print_header "Building Application"
        check_java
        ./gradlew bootJar
        ;;
    clean)
        print_header "Cleaning Build Artifacts"
        ./gradlew clean
        print_success "Clean completed"
        ;;
    run)
        main
        ;;
esac