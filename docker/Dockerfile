# Builds the provider and bakes it into the Keycloak image.
# Context is the repository root: docker compose -f docker/docker-compose.yml build

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

FROM quay.io/keycloak/keycloak:26.8.0

COPY --from=build /workspace/target/mfa-selector-*.jar /opt/keycloak/providers/

# Augments the server with the provider so it is picked up without a runtime rebuild.
RUN /opt/keycloak/bin/kc.sh build --health-enabled=true
