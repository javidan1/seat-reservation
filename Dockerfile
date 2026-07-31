# Build stage: uses the repo's own Gradle wrapper so local and container builds are identical.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies --quiet || true
COPY src ./src
RUN ./gradlew --no-daemon bootJar

# Runtime stage: JRE only.
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
