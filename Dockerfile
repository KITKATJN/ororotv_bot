# --- build: compile and package everything into one jar -------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# no -q here: if compilation fails, the compiler errors must show up in the build logs
RUN mvn -B package -DskipTests

# --- run: only a Java 21 runtime and the jar --------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/ororotv_bot-1.0-SNAPSHOT.jar /app/bot.jar

# SQLite database location; on Railway, attach a volume mounted at /data so it survives redeploys
ENV BOT_DATA_DIR=/data
EXPOSE 8080

# exec form: java runs as PID 1 and receives SIGTERM, so the shutdown hook closes the database cleanly
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/bot.jar"]
