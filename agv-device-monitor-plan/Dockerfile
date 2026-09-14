FROM eclipse-temurin:17-jdk-jammy@sha256:ef4374b4b6b9d813dd3f5b593a35ec9a820cfb64a55994798147cc73435a0208 AS build
# Keep ZIP checksum valid: Wrapper缺少unzip会改下tar.gz，不能沿用ZIP的校验值。
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace
COPY . .
# Shared build cache: 两个镜像共用构建阶段；缓存锁避免并发下载破坏Maven仓库。
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    ./mvnw -B -ntp -Dmaven.test.skip=true package \
    && javac --release 17 -d /probe scripts/HealthCheck.java

FROM eclipse-temurin:17-jre-jammy@sha256:ec72ba5962b45ae4e7f96bfb5ebf6eeb34a488b967f937c8e14f0aaec688954f AS runtime
WORKDIR /opt/agv
COPY --from=build /probe/HealthCheck.class ./
# Non-root runtime: 应用仅写临时目录，不需要root权限。
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/opt/agv/app.jar"]

FROM runtime AS backend
COPY --from=build /workspace/backend/target/backend.jar ./app.jar
EXPOSE 8080

FROM runtime AS simulator
COPY --from=build /workspace/simulator/target/simulator.jar ./app.jar
EXPOSE 8081 1502
