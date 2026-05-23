# 多阶段构建：第一阶段编译，第二阶段运行
FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /app

# 复制父 POM
COPY pom.xml .

# 复制子模块 POM
COPY chatbot-service/pom.xml ./chatbot-service/

# 下载依赖（允许失败，因为后续 package 会重试）
WORKDIR /app/chatbot-service
RUN mvn dependency:resolve dependency:resolve-plugins -B || true

# 复制源码并构建
COPY chatbot-service/src ./src
RUN mvn package -DskipTests -B -o || mvn package -DskipTests -B

# 运行阶段：最小化镜像
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 安装字体（Thymeleaf 模板渲染可能需要）
RUN apk add --no-cache tzdata fontconfig ttf-dejavu \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone

COPY --from=builder /app/chatbot-service/target/*.jar app.jar

# JVM 调优参数（2GB 内存环境）
ENV JAVA_OPTS="-Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
