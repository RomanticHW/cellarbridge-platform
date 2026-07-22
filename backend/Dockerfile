FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN ./mvnw -q -pl backend -am dependency:go-offline
COPY backend backend
RUN ./mvnw -q -pl backend -am -DskipTests package

FROM gcr.io/distroless/java21-debian13:nonroot@sha256:c1ab839be0b871268e437a008e154be87f8fabca0202dcd393633c7b263b8e78
WORKDIR /app
COPY --from=build --chown=65532:65532 /workspace/backend/target/cellarbridge-backend-0.1.0-SNAPSHOT.jar app.jar
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/usr/bin/java", "-jar", "/app/app.jar"]
