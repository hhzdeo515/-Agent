# syntax=docker/dockerfile:1
# ============================================================================
# gw-audit 后端镜像（多阶段构建）
#
# 说明：运行镜像内安装 ffmpeg 是刻意为之——抽音轨、抽关键帧、格式转换与降噪预处理
# 无论走云端解析还是本地解析路线都是必需的本地能力（见 docs/00 §4.3）。
# ============================================================================

# ── 构建阶段 ────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先只复制各模块 pom，利用 Docker 层缓存：
# 只要依赖没变，后续改 Java 代码不会重新下载依赖
COPY pom.xml ./
COPY gw-common/pom.xml gw-common/
COPY gw-domain/pom.xml gw-domain/
COPY gw-infra/pom.xml  gw-infra/
COPY gw-app/pom.xml    gw-app/
COPY gw-boot/pom.xml   gw-boot/
RUN mvn -B -q dependency:go-offline -DskipTests || true

# 再复制源码并打包
COPY . .
RUN mvn -B clean package -DskipTests

# ── 运行阶段 ────────────────────────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache tzdata ffmpeg curl \
 && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
 && echo "Asia/Shanghai" > /etc/timezone

# 以非 root 运行
RUN addgroup -S gw && adduser -S -G gw gw
WORKDIR /app
COPY --from=build /build/gw-boot/target/*.jar app.jar
RUN chown -R gw:gw /app
USER gw

EXPOSE 8080

# MaxRAMPercentage 而非固定 -Xmx：容器内存限额变化时行为更合理
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-Duser.timezone=Asia/Shanghai", \
  "-jar", "app.jar"]
