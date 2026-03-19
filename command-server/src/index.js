const mqtt = require("mqtt");
const express = require("express");
const TelemetryLogger = require("./telemetryLogger");
const CommandPublisher = require("./commandPublisher");

const MQTT_HOST = process.env.MQTT_HOST || "localhost";
const MQTT_PORT = parseInt(process.env.MQTT_PORT || "1883", 10);
const COMMAND_INTERVAL_MS = parseInt(
  process.env.COMMAND_INTERVAL_MS || "10000",
  10
);

const telemetryLogger = new TelemetryLogger();

const mqttUrl = `mqtt://${MQTT_HOST}:${MQTT_PORT}`;
console.log(`[SentinelView] Connecting to MQTT broker at ${mqttUrl}`);

const client = mqtt.connect(mqttUrl, {
  clientId: "command-server-1",
  protocolVersion: 5,
  clean: true,
  reconnectPeriod: 5000,
});

const commandPublisher = new CommandPublisher(
  client,
  telemetryLogger,
  COMMAND_INTERVAL_MS
);

client.on("connect", () => {
  console.log("[SentinelView] Connected to MQTT broker");

  client.subscribe("sentinelview/+/+/telemetry", { qos: 0 }, (err) => {
    if (err) console.error("[SentinelView] Failed to subscribe to telemetry:", err);
    else console.log("[SentinelView] Subscribed to telemetry");
  });

  client.subscribe("sentinelview/+/+/alert", { qos: 1 }, (err) => {
    if (err) console.error("[SentinelView] Failed to subscribe to alerts:", err);
    else console.log("[SentinelView] Subscribed to alerts");
  });

  client.subscribe("sentinelview/+/+/command/ack", { qos: 1 }, (err) => {
    if (err) console.error("[SentinelView] Failed to subscribe to command ACKs:", err);
    else console.log("[SentinelView] Subscribed to command ACKs");
  });

  commandPublisher.start();
});

client.on("message", (topic, payload) => {
  if (topic.endsWith("/telemetry")) {
    telemetryLogger.handleTelemetry(topic, payload);
  } else if (topic.endsWith("/command/ack")) {
    telemetryLogger.handleCommandAck(topic, payload);
  } else if (topic.endsWith("/alert")) {
    telemetryLogger.handleAlert(topic, payload);
  }
});

client.on("error", (err) => {
  console.error("[SentinelView] MQTT error:", err.message);
});

client.on("reconnect", () => {
  console.log("[SentinelView] Reconnecting to MQTT broker...");
});

// Express health/metrics API
const app = express();

app.get("/health", (req, res) => {
  res.json({
    status: "ok",
    mqttConnected: client.connected,
    uptime: process.uptime(),
  });
});

app.get("/metrics", (req, res) => {
  const metrics = {
    ...telemetryLogger.getMetrics(),
    ...commandPublisher.getStats(),
    mqttConnected: client.connected,
    uptimeSeconds: Math.floor(process.uptime()),
  };
  res.json(metrics);
});

const PORT = 3000;
app.listen(PORT, () => {
  console.log(`[SentinelView] Health/metrics API listening on port ${PORT}`);
});

// Graceful shutdown
process.on("SIGTERM", () => {
  console.log("[SentinelView] Shutting down...");
  commandPublisher.stop();
  client.end(false, () => process.exit(0));
});
