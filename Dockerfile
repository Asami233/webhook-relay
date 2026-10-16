# 多阶段构建：只需要 Docker 就能从源码构建并运行（jar 与控制台都在镜像里构建）。
# 测试不在这里跑（Testcontainers 需要 Docker-in-Docker）；CI 已对同一份源码跑 ./mvnw verify。

# ---- 后端 jar ----
FROM eclipse-temurin:21-jdk-alpine AS backend
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw
COPY src src
# 缓存本地 Maven 仓库，重复构建不必重新下载依赖
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && cp target/webhook-relay-*.jar /src/app.jar

# ---- 控制台 ----
FROM node:22-alpine AS console
WORKDIR /src
COPY console/package.json console/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY console ./
RUN npm run build

# ---- 运行 ----
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S relay && adduser -S relay -G relay
WORKDIR /app
COPY --from=backend /src/app.jar app.jar
COPY --from=console /src/dist /app/console
ENV RELAY_CONSOLE_DIR=/app/console
USER relay

# 堆与 GC 由 JAVA_OPTS 决定（见 compose.yaml 的 app 服务与 docs/BENCHMARK.md）；
# ExitOnOutOfMemoryError：OOM 后直接退出由编排重启，而不是带病运行。
ENV JAVA_OPTS="-XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
