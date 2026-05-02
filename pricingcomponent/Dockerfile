# Stage 1: Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Since context is ./pricingcomponent, we copy current dir to current dir
COPY pom.xml .
COPY src ./src

# Build the project
RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre
WORKDIR /app

# The JAR will be in the local target folder of the build stage
COPY --from=build /app/target/*.jar app.jar

RUN mkdir logs
EXPOSE 7070

# Optimized for your high-frequency metrics
CMD ["java", "-XX:+UseZGC", "-jar", "app.jar"]
