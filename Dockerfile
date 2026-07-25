# syntax=docker/dockerfile:1

# ---- Build stage: cache dependencies, then package the executable jar ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Resolve dependencies in their own layer so source-only changes don't
# re-download the whole tree on every build.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Build the jar. Tests run via ./mvnw and CI, not in the image build.
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage: minimal JRE, unprivileged user ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app app
COPY --from=build /build/target/transactions-service-*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
