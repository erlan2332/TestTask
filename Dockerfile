# syntax=docker/dockerfile:1
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
COPY gradlew build.gradle settings.gradle ./
COPY gradle/wrapper/ gradle/wrapper/
RUN chmod +x gradlew
COPY src/main/ src/main/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:21-jre AS runtime

WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app --no-create-home app
COPY --from=build --chown=app:app /workspace/build/libs/app.jar /app/app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
