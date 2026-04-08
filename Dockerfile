# Builder stage
FROM eclipse-temurin:21-jdk AS builder
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
FROM eclipse-temurin:25.0.2_10-jre
WORKDIR /app
COPY --from=builder /app/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]