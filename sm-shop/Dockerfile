# Runtime image: maintained JRE base, non-root user, no database or CMS files baked in.
# Mount persistent data (H2 demo database, local CMS files) as volumes instead.
FROM eclipse-temurin:11-jre-jammy
RUN groupadd --system shopizer && useradd --system --gid shopizer --home /opt/app shopizer \
    && mkdir -p /opt/app /files && chown -R shopizer:shopizer /opt/app /files
WORKDIR /opt/app
COPY --chown=shopizer:shopizer target/shopizer.jar /opt/app/shopizer.jar
USER shopizer
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/opt/app/shopizer.jar"]
