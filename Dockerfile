# =======================
# Stage 1: Build
# =======================
FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /app

RUN apk add --no-cache maven bash git

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src

RUN mvn clean package -DskipTests && \
    test -f target/*.jar

# =======================
# Stage 2: Runtime
# =======================
FROM eclipse-temurin:17-jre-alpine AS runtime

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

ENV JAVA_TOOL_OPTIONS="-Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]