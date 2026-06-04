FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn -B -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre

WORKDIR /app

RUN mkdir -p /app/data /workspace/projects

COPY --from=build /workspace/target/*.jar /app/devcontext.jar

ENV SPRING_DATASOURCE_URL=jdbc:sqlite:/app/data/devcontext.sqlite
ENV DEVCONTEXT_LLM_PROVIDER=mock
ENV DEVCONTEXT_VECTOR_PROVIDER=jdbc

EXPOSE 18080

ENTRYPOINT ["java", "-jar", "/app/devcontext.jar"]
