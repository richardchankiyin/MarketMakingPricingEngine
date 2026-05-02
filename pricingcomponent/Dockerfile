# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app
# Dynamically find the jar name to avoid versioning issues
COPY --from=build /app/target/*.jar app.jar
RUN mkdir logs
EXPOSE 7070
CMD ["java", "-jar", "app.jar"]
