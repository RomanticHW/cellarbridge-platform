FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN ./mvnw -q -pl backend -am dependency:go-offline
COPY backend backend
RUN ./mvnw -q -pl backend -am -DskipTests package

FROM cgr.dev/chainguard/wolfi-base:latest@sha256:02dab76bd852a70556b5b2002195c8a5fdab77d323c433bf6642aab080489795
USER 0
RUN apk add --no-cache openjdk-21-jre=21.0.11-r3 wget=1.25.0-r14
WORKDIR /app
COPY --from=build --chown=65532:65532 /workspace/backend/target/cellarbridge-backend-0.1.0-SNAPSHOT.jar app.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/lib/jvm/java-21-openjdk/bin/java", "-jar", "/app/app.jar"]
