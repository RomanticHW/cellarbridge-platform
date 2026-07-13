FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23 AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY backend/pom.xml backend/pom.xml
RUN ./mvnw -q -pl backend -am dependency:go-offline
COPY backend backend
RUN ./mvnw -q -pl backend -am -DskipTests package

FROM eclipse-temurin:21.0.11_10-jre-alpine-3.23
RUN addgroup -S cellarbridge && adduser -S cellarbridge -G cellarbridge
WORKDIR /app
COPY --from=build /workspace/backend/target/cellarbridge-backend-0.1.0-SNAPSHOT.jar app.jar
USER cellarbridge
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
