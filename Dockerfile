# Builder stage
# Pin base image to specific digest for reproducible builds and security auditing
FROM eclipse-temurin:21-jdk@sha256:8e57453df443b4cf4e5a61c7076201e2c1bcc6d832dfb67d2e83b642a1d44eb6 AS builder
WORKDIR /app

COPY gradle/wrapper gradle/wrapper
COPY gradlew .
COPY build.gradle.kts .
COPY settings.gradle.kts .
RUN chmod +x gradlew

# Download dependencies — this layer is cached as long as build.gradle.kts doesn't change
RUN ./gradlew dependencies --no-daemon

# Now copy source — cache only invalidates from here on source changes
COPY src src
RUN ./gradlew build -x test --no-daemon

# Runtime stage
# Pin base image to specific digest for reproducible builds and security auditing
FROM eclipse-temurin:25.0.2_10-jre@sha256:0d294ec3a725eacae55227affb61509cd3aef6316ce390e5d49b00eab4068e5d
WORKDIR /app

# Apply security patches as root
RUN apt-get update && \
    apt-get install -y --no-install-recommends --only-upgrade \
      systemd \
      libsystemd0 \
      libudev1 && \
    if id -u ubuntu >/dev/null 2>&1; then userdel -r ubuntu; fi && \
    useradd -m -u 1000 mq-user && \
    rm -rf /var/lib/apt/lists/*

# Copy with proper ownership and drop privileges
COPY --from=builder --chown=mq-user:mq-user /app/build/libs/simple-mq-0.0.1-SNAPSHOT.jar app.jar
USER mq-user
ENTRYPOINT ["java", "-jar", "app.jar"]