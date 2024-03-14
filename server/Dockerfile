FROM maven:3.9.6-eclipse-temurin-21

WORKDIR /app
COPY . .
RUN --mount=type=cache,target=/root/.m2 \
  mvn clean package
CMD ["java", "-jar", "/app/target/server-1.0-SNAPSHOT.jar"]
