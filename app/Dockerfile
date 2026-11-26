# Đóng gói ứng dụng để chạy thật (mốc 1F). Build hai tầng: tầng JDK biên dịch, tầng JRE chỉ chạy.
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw --batch-mode --no-transfer-progress -q dependency:go-offline
COPY src src
ARG BUILD_REVISION=local
RUN printf '%s' "$BUILD_REVISION" | grep -Eq '^(local|[0-9a-f]{40})$' \
    && printf '%s\n' "$BUILD_REVISION" > src/main/resources/adh-build-revision.txt \
    && ./mvnw --batch-mode --no-transfer-progress -q -DskipTests package \
    && mv target/crm-*.jar /src/app.jar

FROM eclipse-temurin:25-jre
ARG BUILD_REVISION=local
LABEL org.opencontainers.image.revision="$BUILD_REVISION" vn.shop.crm.readiness="1"
# Chạy bằng người dùng thường, không phải root.
RUN useradd --system --uid 10001 --no-create-home crm
WORKDIR /app
COPY --from=build /src/app.jar /app/app.jar
USER crm
EXPOSE 8080
# Máy chủ 2 GB RAM: giới hạn bộ nhớ Java khoảng 512 MB, nhường chỗ cho PostgreSQL và hệ điều hành.
# Biến môi trường có chữ có dấu (ví dụ APP_SHOP_NAME) chỉ đọc đúng khi hệ thống dùng bảng mã UTF-8.
ENV LC_ALL=C.UTF-8
ENV JAVA_TOOL_OPTIONS="-Xms128m -Xmx512m -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
