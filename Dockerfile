# Two-stage build from source. Build:  docker build -t shopizer-api .
# Stage 1 compiles with the same JDK the CI uses; stage 2 is a small non-root JRE image.
FROM maven:3.9-eclipse-temurin-26 AS build
WORKDIR /src
# dependency layer first, so code changes do not re-download the world
COPY pom.xml ./
COPY sm-core-model/pom.xml sm-core-model/
COPY sm-core-modules/pom.xml sm-core-modules/
COPY sm-core/pom.xml sm-core/
COPY sm-shop-model/pom.xml sm-shop-model/
COPY sm-shop/pom.xml sm-shop/
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline -DexcludeReactor=true || true
COPY . .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests -Dmaven.javadoc.skip=true package -pl sm-shop -am

FROM eclipse-temurin:11-jre-jammy
RUN groupadd --system shopizer && useradd --system --gid shopizer --home /opt/app shopizer \
    && apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/app /files && chown -R shopizer:shopizer /opt/app /files
WORKDIR /opt/app
COPY --from=build --chown=shopizer:shopizer /src/sm-shop/target/shopizer.jar /opt/app/shopizer.jar
USER shopizer
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=180s --retries=5 \
  CMD curl -fs http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/opt/app/shopizer.jar"]
