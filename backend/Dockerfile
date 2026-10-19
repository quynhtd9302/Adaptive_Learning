# ---- Build ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B clean package -DskipTests

# ---- Run ----
FROM eclipse-temurin:17-jre
WORKDIR /app
RUN useradd --system --uid 1001 app && mkdir -p /app/uploads && chown app /app/uploads
COPY --from=build /src/target/app.jar app.jar
USER app
EXPOSE 8080
# JVM dung toi da 75% RAM cua task ECS (task 1GB -> heap ~750MB)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
