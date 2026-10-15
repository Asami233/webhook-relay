# 运行镜像：先在宿主机构建 jar（./mvnw -DskipTests package），这里只负责运行。
# 不在镜像里跑 Maven：1 核小机器上构建很慢，CI 已经构建并测试过同一个 jar。
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S relay && adduser -S relay -G relay
WORKDIR /app
COPY target/webhook-relay-*.jar app.jar
USER relay

# 堆与 GC 由 JAVA_OPTS 决定（见 compose.yaml 的 app 服务与 docs/BENCHMARK.md）；
# ExitOnOutOfMemoryError：OOM 后直接退出由编排重启，而不是带病运行。
ENV JAVA_OPTS="-XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
