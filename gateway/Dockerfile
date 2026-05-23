# Gateway 多阶段构建
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# 复制父 POM
COPY pom.xml .

# 复制子模块 POM
COPY gateway/pom.xml ./gateway/

# 下载依赖（允许失败，因为后续 package 会重试）
WORKDIR /app/gateway
RUN mvn dependency:resolve dependency:resolve-plugins -B || true

# 复制源码并构建
COPY gateway/src ./src
RUN mvn package -DskipTests -B -o || mvn package -DskipTests -B

# 运行阶段
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN apk add --no-cache tzdata \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

COPY --from=builder /app/gateway/target/*.jar app.jar

ENV JAVA_OPTS="-Xms128m -Xmx256m -XX:+UseG1GC"

EXPOSE 9000

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
