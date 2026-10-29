# syntax=docker/dockerfile:1
# 빌드는 컨테이너 안에서 한다. 홈서버에 JDK를 설치하지 않는다.
# 사용: docker build -f docker/Dockerfile --target build .   # gradle build (린트, 테스트, bootJar)
#       docker build -f docker/Dockerfile --build-arg MODULE=api -t stock-guide-api .

FROM eclipse-temurin:25-jdk AS source
WORKDIR /workspace
COPY . .

FROM source AS build
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain build

FROM source AS package
ARG MODULE
RUN test -n "$MODULE" || { echo "MODULE 빌드 인자가 필요하다 (api 또는 scheduler)"; exit 1; }
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon --console=plain ":${MODULE}:bootJar" \
    && cp "${MODULE}/build/libs/app.jar" /app.jar

FROM eclipse-temurin:25-jre-alpine AS runtime
RUN addgroup -S app && adduser -S app -G app
USER app
WORKDIR /app
COPY --from=package /app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
