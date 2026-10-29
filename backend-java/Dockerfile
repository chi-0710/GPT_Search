FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace

ARG MAVEN_MIRROR_URL=https://maven.aliyun.com/repository/public
ENV MAVEN_MIRROR_URL=${MAVEN_MIRROR_URL}

COPY pom.xml maven-settings.xml ./
COPY src src
RUN --mount=type=cache,id=researchrunner-maven,target=/root/.m2/repository,sharing=locked \
    timeout --signal=TERM 20m \
    mvn -B -ntp -s maven-settings.xml verify

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=build /workspace/target/researchrunner-api-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
