FROM eclipse-temurin:17-jdk AS builder
WORKDIR /workspace
COPY settings.gradle build.gradle gradlew gradlew.bat /workspace/
COPY gradle /workspace/gradle
COPY heuristic-exception /workspace/heuristic-exception
RUN ./gradlew :heuristic-exception:bootJar --no-daemon

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=builder /workspace/heuristic-exception/build/libs/heuristic-exception-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
