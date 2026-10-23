# 운영 이미지. 실행 시 DB_URL, DB_USERNAME, DB_PASSWORD, REDIS_HOST, JWT_SECRET 환경변수가 필요하다.
# (application-prod.properties 참고. 빠지면 기동 즉시 실패한다)

# 1단계: 빌드. 테스트는 CI 가 담당하므로 여기서는 돌리지 않는다. (Testcontainers 는 빌드 컨테이너 안에서 Docker 를 쓸 수 없다)
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# 빌드 스크립트만 먼저 복사해 의존성을 받아 둔다. 소스만 바뀌면 이 레이어를 캐시에서 재사용한다.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew dependencies --no-daemon > /dev/null

COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# 2단계: 실행. JDK 전체가 아니라 JRE 만 담는다.
FROM eclipse-temurin:21-jre
WORKDIR /app

# 로그 시각을 한국 시간으로 보기 위한 설정. 서비스 로직의 시간대는 코드(ClockConfig)에 고정돼 있어 이 값과 무관하다.
ENV TZ=Asia/Seoul \
    SPRING_PROFILES_ACTIVE=prod \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

# root 가 아닌 사용자로 실행한다
RUN groupadd --system app && useradd --system --gid app app
COPY --from=builder /app/build/libs/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
