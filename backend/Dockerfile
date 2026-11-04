FROM maven:3.9-eclipse-temurin-17 AS build

WORKDIR /workspace

COPY pom.xml ./
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline

COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package \
    && cp target/tss-backend-*.jar /workspace/app.jar

FROM eclipse-temurin:17-jre-jammy

RUN groupadd --gid 10001 app \
    && useradd --uid 10001 --gid app --create-home --shell /usr/sbin/nologin app

WORKDIR /app
COPY --from=build /workspace/app.jar ./app.jar
RUN mkdir -p /app/logs \
    && chown -R app:app /app

USER app
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
