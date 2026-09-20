FROM maven:3.8.8-eclipse-temurin-11 AS build
WORKDIR /workspace
COPY backend/pom.xml backend/pom.xml
COPY contracts contracts
COPY backend/src backend/src
COPY frontend frontend
COPY Датасет Датасет
RUN mvn -B -f backend/pom.xml verify

FROM eclipse-temurin:11-jre-jammy
WORKDIR /app
RUN groupadd --system heatnet && useradd --system --gid heatnet heatnet && mkdir -p /data/uploads && chown -R heatnet:heatnet /data
COPY --from=build /workspace/backend/target/heatnet.jar /app/heatnet.jar
USER heatnet
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/heatnet.jar"]
