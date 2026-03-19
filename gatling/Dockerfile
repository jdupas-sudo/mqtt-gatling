FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app
COPY pom.xml .
RUN mvn dependency:resolve -q

COPY src/ ./src/
RUN mvn test-compile -q

FROM maven:3.9-eclipse-temurin-17

WORKDIR /app
COPY --from=builder /root/.m2 /root/.m2
COPY --from=builder /app /app

ENV MQTT_HOST=localhost
ENV MQTT_PORT=1883
ENV GATLING_USERS=500
ENV GATLING_DURATION=300
ENV SIMULATION_CLASS=sentinelview.HeartbeatFloodSimulation

ENTRYPOINT ["sh", "-c", "mvn gatling:test -Dgatling.simulationClass=$SIMULATION_CLASS -Dgatling.broker.host=$MQTT_HOST -Dgatling.broker.port=$MQTT_PORT -Dgatling.users=$GATLING_USERS -Dgatling.duration=$GATLING_DURATION"]
