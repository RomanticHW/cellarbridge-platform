FROM quay.io/keycloak/keycloak:26.7.0@sha256:0f198be292568439d700cdbfb893e69a6009bb43a94a06a945b1d3d506c76b13 AS keycloak-base

FROM eclipse-temurin:21.0.11_10-jdk-alpine-3.23@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS provider
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY keycloak-resource-binding keycloak-resource-binding
COPY --from=keycloak-base /opt/keycloak/bin/client/keycloak-admin-cli-26.7.0.jar /tmp/keycloak-admin-cli.jar
ARG PATCHED_LIBRARIES="com.fasterxml.jackson.core:jackson-core:2.21.5 com.fasterxml.jackson.core:jackson-databind:2.21.5 io.netty:netty-codec:4.1.136.Final io.netty:netty-codec-dns:4.1.136.Final io.netty:netty-codec-haproxy:4.1.136.Final io.netty:netty-codec-http:4.1.136.Final io.netty:netty-codec-http2:4.1.136.Final io.netty:netty-codec-socks:4.1.136.Final org.postgresql:postgresql:42.7.12"
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -f keycloak-resource-binding/pom.xml -DskipTests package && for artifact in ${PATCHED_LIBRARIES}; do \
    ./mvnw -q -f keycloak-resource-binding/pom.xml org.apache.maven.plugins:maven-dependency-plugin:3.9.0:copy -Dartifact="${artifact}" -DoutputDirectory=/patched; \
  done && mkdir -p /tmp/postgresql-meta/META-INF/maven/org.postgresql/postgresql && \
  printf 'groupId=org.postgresql\nartifactId=postgresql\nversion=42.7.12\n' > /tmp/postgresql-meta/META-INF/maven/org.postgresql/postgresql/pom.properties && \
  jar --update --file /patched/postgresql-42.7.12.jar -C /tmp/postgresql-meta META-INF && \
  mkdir /tmp/keycloak-admin-cli && cd /tmp/keycloak-admin-cli && jar xf /tmp/keycloak-admin-cli.jar && \
  cp META-INF/MANIFEST.MF /tmp/keycloak-admin-cli.mf && \
  rm -rf com/fasterxml/jackson/core com/fasterxml/jackson/databind \
    META-INF/maven/com.fasterxml.jackson.core/jackson-core META-INF/maven/com.fasterxml.jackson.core/jackson-databind \
    META-INF/versions/*/com/fasterxml/jackson/core && \
  for patch in /patched/jackson-core-2.21.5.jar /patched/jackson-databind-2.21.5.jar; do jar xf "${patch}"; done && \
  rm -f META-INF/MANIFEST.MF META-INF/*.RSA META-INF/*.SF META-INF/*.DSA && \
  jar cfm /patched/keycloak-admin-cli-26.7.0.jar /tmp/keycloak-admin-cli.mf -C /tmp/keycloak-admin-cli .

FROM keycloak-base AS keycloak
COPY --from=provider /workspace/keycloak-resource-binding/target/cellarbridge-keycloak-resource-binding-1.0.0.jar /opt/keycloak/providers/
COPY --from=provider --chown=1000:0 /patched/ /tmp/patched/
RUN cp /tmp/patched/keycloak-admin-cli-26.7.0.jar /opt/keycloak/bin/client/ && \
  for library in jackson-core jackson-databind; do \
    cp "/tmp/patched/${library}-2.21.5.jar" "/opt/keycloak/lib/lib/main/com.fasterxml.jackson.core.${library}-2.21.2.jar"; \
  done && for library in netty-codec netty-codec-dns netty-codec-haproxy netty-codec-http netty-codec-http2 netty-codec-socks; do \
    cp "/tmp/patched/${library}-4.1.136.Final.jar" "/opt/keycloak/lib/lib/main/io.netty.${library}-4.1.135.Final.jar"; \
  done && cp /tmp/patched/postgresql-42.7.12.jar /opt/keycloak/lib/lib/main/org.postgresql.postgresql-42.7.11.jar && \
  rm -rf /tmp/patched && /opt/keycloak/bin/kc.sh build \
    --health-enabled=true \
    --spi-client-policy-executor--cellarbridge-mcp-resource-binding--enabled=true \
    --spi-token-interceptor--cellarbridge-mcp-resource-binding--enabled=true

FROM cgr.dev/chainguard/wolfi-base:latest@sha256:02dab76bd852a70556b5b2002195c8a5fdab77d323c433bf6642aab080489795
USER 0
RUN apk add --no-cache openjdk-21-jre=21.0.11-r3 wget=1.25.0-r14
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk PATH=/usr/lib/jvm/java-21-openjdk/bin:$PATH KC_RUN_IN_CONTAINER=true LANG=en_US.UTF-8
COPY --from=keycloak --chown=1000:0 /opt/keycloak /opt/keycloak
USER 1000:0
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
