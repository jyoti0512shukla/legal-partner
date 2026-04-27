FROM gradle:8.7-jdk21 AS build
WORKDIR /app
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle/ gradle/
RUN gradle dependencies --no-daemon || true
COPY src/ src/
RUN gradle bootJar --no-daemon

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl su-exec
RUN addgroup -S app && adduser -S app -G app
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
COPY <<'EOF' /entrypoint.sh
#!/bin/sh
# Fix ownership of mounted volumes (they come in as root from Docker)
chown -R app:app /data/documents 2>/dev/null || true
# Drop to non-root and run the app
exec su-exec app java -jar /app/app.jar
EOF
RUN chmod +x /entrypoint.sh
RUN mkdir -p /data/documents
EXPOSE 8080
ENTRYPOINT ["/entrypoint.sh"]
