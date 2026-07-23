FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23 AS provider
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY keycloak-resource-binding/pom.xml keycloak-resource-binding/pom.xml
RUN ./mvnw -q -f keycloak-resource-binding/pom.xml dependency:go-offline
COPY keycloak-resource-binding/src keycloak-resource-binding/src
RUN ./mvnw -q -f keycloak-resource-binding/pom.xml -DskipTests package

FROM quay.io/keycloak/keycloak:26.7.0
COPY --from=provider /workspace/keycloak-resource-binding/target/cellarbridge-keycloak-resource-binding-1.0.0.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build
