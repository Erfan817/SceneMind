# 多阶段构建：Maven 打包 -> JRE 运行时（含 FFmpeg / Tesseract / yt-dlp）
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY server/pom.xml ./
COPY server/.mvn .mvn
COPY server/mvnw ./
RUN bash ./mvnw -B -q -DskipTests dependency:go-offline
COPY server/src ./src
RUN bash ./mvnw -B -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ffmpeg \
        tesseract-ocr \
        tesseract-ocr-chi-sim \
        tesseract-ocr-eng \
        curl \
        ca-certificates \
    && curl -fsSL https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp \
        -o /usr/local/bin/yt-dlp \
    && chmod +x /usr/local/bin/yt-dlp \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC -Duser.timezone=Asia/Shanghai" \
    TZ=Asia/Shanghai
EXPOSE 9090

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
