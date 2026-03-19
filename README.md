# SentinelView — MQTT Load Testing Suite

IoT Security Camera Fleet simulation for **Gatling Enterprise**, fully Dockerized.

## Architecture

```
┌──────────────┐     MQTT 5      ┌───────────┐     MQTT 5      ┌──────────────────┐
│  Gatling      │ ──────────────► │   EMQX    │ ◄────────────── │  Command Server  │
│  (load test)  │ telemetry/alert │  Broker   │  commands/acks  │  (Node.js)       │
└──────────────┘                  └───────────┘                  └──────────────────┘
                                   :1883 TCP
                                   :8083 WS
                                   :18083 Dashboard
```

Three services in a single `docker compose` stack:

| Service | Role | Ports |
|---------|------|-------|
| **emqx** | MQTT 5 broker | 1883, 8083, 18083 |
| **command-server** | Cloud backend simulator | 3000 |
| **gatling** | Load test runner | — |

## Quick Start

```bash
# Start everything (runs HeartbeatFloodSimulation by default)
docker compose up --build

# Run a specific simulation
SIMULATION_CLASS=sentinelview.AlertStormSimulation docker compose up --build gatling

# Available simulations:
#   sentinelview.HeartbeatFloodSimulation  — Publish-only baseline
#   sentinelview.AlertStormSimulation      — Bidirectional pub/sub + command/ack
#   sentinelview.ConnectionChurnSimulation — Rapid connect/disconnect cycles
#   sentinelview.StressTestSimulation      — Staircase ramp to find breaking point
```

## Monitoring

- **EMQX Dashboard**: http://localhost:18083 (admin / public)
- **Command Server Metrics**: http://localhost:3000/metrics
- **Command Server Health**: http://localhost:3000/health

## MQTT Topic Hierarchy

```
sentinelview/{siteId}/{cameraId}/telemetry      QoS 0  Camera → Broker
sentinelview/{siteId}/{cameraId}/alert           QoS 1  Camera → Broker
sentinelview/{siteId}/{cameraId}/command          QoS 1  Broker → Camera
sentinelview/{siteId}/{cameraId}/command/ack      QoS 1  Camera → Broker
```

## Simulations

### 1. HeartbeatFloodSimulation (Baseline)
Publish-only. Each camera publishes telemetry every 5s. Validates broker throughput for high-frequency, low-payload traffic.

### 2. AlertStormSimulation (Showcase)
Full bidirectional traffic. Cameras publish telemetry + alerts, subscribe to commands, and respond with ACKs. Models a peak event like a warehouse shift change.

### 3. ConnectionChurnSimulation (Resilience)
Rapid connect/disconnect cycles simulating camera reboots, Wi-Fi drops, and firmware updates. Reveals memory leaks and session cleanup issues.

### 4. StressTestSimulation (Capacity)
Staircase load pattern that doubles concurrent cameras every `duration/5` seconds to find the broker's breaking point. Each stage ramps in new users while all previous users keep publishing, so connections accumulate:

| Stage | New users added | Total concurrent |
|-------|----------------|-----------------|
| 1 | `users × 1` | `users × 1` |
| 2 | `users × 1` | `users × 2` |
| 3 | `users × 2` | `users × 4` |
| 4 | `users × 4` | `users × 8` |
| 5 | `users × 8` | `users × 16` |

Examples with `gatling.duration=300` (60s per stage):

| `gatling.users` | Peak concurrent | Recommended for |
|-----------------|----------------|-----------------|
| 50 | 800 | Initial validation |
| 200 | 3,200 | Medium stress |
| 500 | 8,000 | Heavy stress |

Look for the inflection point in the Gatling Enterprise report where response times spike or error rates climb.

### 5. RoundTripSimulation (End-to-End Validation)
Measures full round-trip latency: camera publishes an alert → command-server receives it and immediately sends a command back → camera receives the command. Uses Gatling's `.expect()` and `.check()` to validate that the response arrives within 5 seconds and contains a valid `commandType`. Best run with fewer users (e.g. 50) — the goal is latency measurement, not volume.

```
Camera → alert → EMQX → command-server → command → EMQX → Camera
```


## Configuration

All parameters are configurable via environment variables (`.env` or inline):

| Variable | Default | Description |
|----------|---------|-------------|
| `SIMULATION_CLASS` | `sentinelview.HeartbeatFloodSimulation` | Gatling simulation to run |
| `GATLING_USERS` | `500` | Number of virtual cameras |
| `GATLING_DURATION` | `300` | Test duration in seconds |
| `GATLING_BROKER_HOST` | `emqx` | MQTT broker hostname |
| `GATLING_BROKER_PORT` | `1883` | MQTT broker port |
| `CAMERAS_PER_SITE` | `100` | Cameras per site (command-server) |
| `COMMAND_INTERVAL_MS` | `10000` | Command publish interval (ms) |

## Gatling Enterprise Deployment

```bash
cd gatling
mvn gatling:enterprisePackage
```

Upload the resulting JAR to Gatling Enterprise. Configure these **system properties** (Java System Properties in the Gatling Enterprise simulation settings):

| Property | Default | Description |
|----------|---------|-------------|
| `gatling.broker.host` | `localhost` | MQTT broker hostname or IP |
| `gatling.broker.port` | `1883` | MQTT broker port |
| `gatling.users` | `500` | Number of virtual cameras to inject |
| `gatling.duration` | `300` | Test duration in seconds |

### Running from Gatling Enterprise (with local broker)

When Gatling Enterprise load generators run on AWS and your EMQX broker runs on your local machine, you need a TCP tunnel to make it reachable.

#### Step 1 — Start the local infrastructure (no Gatling)

```bash
docker compose up --build emqx command-server
```

This starts EMQX and the command-server locally. The `gatling` service is skipped — Gatling Enterprise handles load injection.

#### Step 2 — Expose MQTT via ngrok

```bash
# Install ngrok (macOS)
brew install ngrok

# Authenticate (free account, one-time setup)
ngrok config add-authtoken <your-token-from-https://dashboard.ngrok.com>

# Expose the MQTT port
ngrok tcp 1883
```

ngrok will display a forwarding address like:

```
Forwarding   tcp://0.tcp.eu.ngrok.io:16077 -> localhost:1883
```

#### Step 3 — Configure Gatling Enterprise simulation

ngrok will display a forwarding line like:

```
Forwarding   tcp://<host>:<port> -> localhost:1883
```

Extract the **host** and **port** from this line and set them as **Java System Properties** in your Gatling Enterprise simulation:

| Property | Value |
|----------|-------|
| `gatling.broker.host` | `<host>` from the ngrok forwarding address |
| `gatling.broker.port` | `<port>` from the ngrok forwarding address |
| `gatling.users` | Number of virtual cameras to inject |
| `gatling.duration` | Test duration in seconds |

> **Tip:** Start with a low user count and short duration to validate the tunnel works end-to-end, then ramp up gradually. The free ngrok plan has bandwidth limits, and the tunnel adds latency — absolute response times will be higher than in production.

#### Traffic flow

```
Gatling Enterprise (AWS) → ngrok (tcp tunnel) → your machine:1883 → EMQX → command-server
```

#### Alternative tunneling tools

| Tool | Install | Command | Notes |
|------|---------|---------|-------|
| **ngrok** | `brew install ngrok` | `ngrok tcp 1883` | Most reliable, free plan has limits |
| **bore** | `brew install bore-cli` | `bore local 1883 --to bore.pub` | Open source, free relay can be unstable |
| **Cloudflare Tunnel** | `brew install cloudflared` | Requires a domain + config | Free, no bandwidth limits for TCP |

## Language: Scala or Java

The simulations are available in two flavors — **pick whichever your team is more comfortable with**. Both implement the exact same scenarios with the same system properties.

| | Scala (`gatling/`) | Java (`gatling-java/`) |
|---|---|---|
| Language | Scala 2.13 | Java 17 |
| MQTT dependency | `gatling-mqtt` | `gatling-mqtt-java` |
| Run locally | `docker compose up --build gatling` | `docker compose up --build gatling-java` |
| Package for Enterprise | `cd gatling && mvn gatling:enterprisePackage` | `cd gatling-java && mvn gatling:enterprisePackage` |

> **Note:** All testing and validation for this project was done with the **Scala** variant. The Java version is a direct port and should behave identically, but if you encounter discrepancies, the Scala version is the reference implementation.

## Tech Stack

- **Gatling 3.13+** with MQTT plugin (Scala 2.13 or Java 17)
- **EMQX 5.8** (MQTT 5 broker)
- **Node.js 20** (command server)
- **Docker Compose 3.8+**
# mqtt-gatling
