FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY maven-settings.xml .
RUN mvn -B -ntp -s maven-settings.xml dependency:go-offline
COPY src src
RUN mvn -B -ntp -s maven-settings.xml verify

FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
COPY --from=build /workspace/target/researchrunner-api-0.1.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8000
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
