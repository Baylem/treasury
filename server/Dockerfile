# Build the distribution first: ./gradlew :server:installDist
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 treasury \
    && useradd --uid 10001 --gid treasury --no-create-home treasury

WORKDIR /opt/treasury
COPY --chown=10001:10001 server/build/install/server/ /opt/treasury/
RUN chmod +x /opt/treasury/bin/server
USER 10001:10001
ENV JAVA_OPTS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=45s --retries=3 \
    CMD curl --fail --silent http://127.0.0.1:8080/health/ready || exit 1
ENTRYPOINT ["/opt/treasury/bin/server"]
