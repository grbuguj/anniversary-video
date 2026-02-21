FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .
RUN chmod +x ./gradlew
RUN ./gradlew dependencies --no-daemon 2>/dev/null || true

COPY src src
RUN ./gradlew bootJar --no-daemon -x test

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache ffmpeg tzdata
ENV TZ=Asia/Seoul

WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8081
ENTRYPOINT ["java", "-jar", "-Dspring.profiles.active=${SPRING_PROFILES_ACTIVE:prod}", "app.jar"]
