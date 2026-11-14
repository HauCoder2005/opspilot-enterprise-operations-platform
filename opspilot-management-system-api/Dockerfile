FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /workspace
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/
RUN chmod +x mvnw && ./mvnw -B -DskipTests clean package

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S opspilot && adduser -S -G opspilot opspilot
WORKDIR /app
COPY --from=build --chown=opspilot:opspilot /workspace/target/opspilot-system-business-*.jar app.jar
USER opspilot
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
