# --------------------------------------------------------------------------------------------------
# Runtime image
#
# The boot jar is built outside the image. This Dockerfile is meant to be used through
# docker/build-image.sh, which prepares a temporary build context containing just this file and the
# jar; that is why there is no .dockerignore and no source tree in the context.
#
# Manual use: prepare the context yourself, for example
#   tmp=$(mktemp -d)
#   cp docker/Dockerfile "$tmp/"
#   cp app/build/libs/*-SNAPSHOT.jar "$tmp/application.jar"
#   docker build -t coupon-service "$tmp"
#   rm -rf "$tmp"
# --------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Unprivileged: nothing here needs root, and a compromised process should not own the container.
RUN addgroup -S app && adduser -S --ingroup app app

COPY --chown=app:app application.jar ./application.jar

USER app
# Documentation only: EXPOSE publishes nothing, the caller decides what to map (docker run -p, a
# compose service, a Kubernetes Service). 8080 is the API, 8081 the management connector, which is
# meant to stay internal: only a scraper inside the network should reach it.
EXPOSE 8080 8081

# 75% of the container's memory limit: the rest is metaspace, code cache, thread stacks and the OS.
# JAVA_TOOL_OPTIONS remains available to override anything at run time.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

# Liveness: the container is restarted when the JVM itself is wedged. Database and Redis outages
# show up in /actuator/health/readiness instead, which is what a load balancer should gate on. Both
# are on the management port.
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8081/actuator/health/liveness | grep -q '"status":"UP"' || exit 1

# Exec form, so the JVM is PID 1 and receives SIGTERM directly: `docker stop` then triggers the
# graceful shutdown configured in application.yaml instead of a kill after the timeout.
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
