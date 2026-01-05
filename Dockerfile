# syntax=docker/dockerfile:1

# Build stage - using Java 21 for compatibility
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /workspace

# Copy Gradle wrapper and build files
COPY gradlew gradlew
COPY gradle/ gradle/
COPY build.gradle .
COPY settings.gradle .

# Download dependencies
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew dependencies --no-daemon

# Copy source code
COPY src src/

# Build the application
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon && \
    mv build/libs/webhook-service-*.jar /workspace/app.jar

# Extract layers for better caching
FROM builder AS extract
WORKDIR /workspace
RUN java -Djarmode=layertools -jar app.jar extract --destination extracted

# Final runtime stage
FROM eclipse-temurin:21-jre-jammy AS final

# Create non-root user
ARG UID=10001
RUN adduser \
    --disabled-password \
    --gecos "" \
    --home "/nonexistent" \
    --shell "/sbin/nologin" \
    --no-create-home \
    --uid "${UID}" \
    appuser

WORKDIR /app

# Copy application layers from extract stage
COPY --from=extract --chown=appuser:appuser /workspace/extracted/dependencies/ ./
COPY --from=extract --chown=appuser:appuser /workspace/extracted/spring-boot-loader/ ./
COPY --from=extract --chown=appuser:appuser /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extract --chown=appuser:appuser /workspace/extracted/application/ ./

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Set Spring profile
ENV SPRING_PROFILES_ACTIVE=prod

# Run the application with native access enabled for Java 21+ compatibility
ENTRYPOINT ["java", \
    "-Djava.security.egd=file:/dev/./urandom", \
    "-XX:+UseContainerSupport", \
    "-XX:MaxRAMPercentage=75.0", \
    "--enable-native-access=ALL-UNNAMED", \
    "org.springframework.boot.loader.launch.JarLauncher"]