package sentinelview;

import io.gatling.javaapi.mqtt.MqttProtocolBuilder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.gatling.javaapi.mqtt.MqttDsl.*;

/**
 * Shared MQTT protocol configuration, feeders, and payload generators
 * used by all SentinelView simulations.
 *
 * All parameters are configurable via Java system properties (-D flags),
 * which Gatling Enterprise injects from the simulation settings UI.
 * When running locally via Docker, the defaults apply.
 */
public final class SentinelViewProtocol {

    // --- Configurable parameters (set via -Dgatling.xxx or Gatling Enterprise system properties) ---

    /** MQTT broker hostname. Override with -Dgatling.broker.host=0.tcp.eu.ngrok.io */
    public static final String BROKER_HOST = System.getProperty("gatling.broker.host", "localhost");

    /** MQTT broker port. Override with -Dgatling.broker.port=16077 */
    public static final int BROKER_PORT = Integer.parseInt(System.getProperty("gatling.broker.port", "1883"));

    /** Number of virtual cameras to inject. Override with -Dgatling.users=50 */
    public static final int USERS = Integer.parseInt(System.getProperty("gatling.users", "500"));

    /** Total test duration in seconds. Override with -Dgatling.duration=60 */
    public static final int DURATION_SEC = Integer.parseInt(System.getProperty("gatling.duration", "300"));

    // --- Constants ---

    /** 20 simulated retail sites; each camera is randomly assigned to one */
    public static final List<String> SITE_IDS = IntStream.rangeClosed(1, 20)
            .mapToObj(i -> String.format("site-retail-%03d", i))
            .collect(Collectors.toUnmodifiableList());

    /**
     * MQTT 5 protocol shared by all simulations.
     * - clientId uses Gatling EL #{userId} populated by the camera feeder
     * - cleanSession=true so each virtual user starts with no server-side state
     */
    public static final MqttProtocolBuilder MQTT_PROTOCOL = mqtt
            .mqttVersion_5()
            .broker(BROKER_HOST, BROKER_PORT)
            .clientId("sv-cam-#{userId}")
            .cleanSession(true)
            .reconnectAttemptsMax(3)
            .reconnectDelay(1)
            .reconnectBackoffMultiplier(1.5f);

    /**
     * Infinite feeder that assigns each virtual user a random site + camera identity.
     * Populates session attributes: siteId, cameraId, userId.
     */
    public static Iterator<Map<String, Object>> cameraFeeder() {
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                return true; // infinite — Gatling pulls one entry per virtual user
            }

            @Override
            public Map<String, Object> next() {
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                String siteId = SITE_IDS.get(rnd.nextInt(SITE_IDS.size()));
                int camIdx = rnd.nextInt(10000);
                String cameraId = String.format("cam-%04d", camIdx);
                return Map.of(
                        "siteId", siteId,
                        "cameraId", cameraId,
                        "userId", siteId + "-" + cameraId
                );
            }
        };
    }

    /** Generates a realistic camera telemetry JSON payload with randomized metrics. */
    public static String telemetryPayload(String siteId, String cameraId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double cpu = 20 + rnd.nextDouble() * 60;       // 20-80%
        double temp = 30 + rnd.nextDouble() * 25;       // 30-55°C
        double fps = 24 + rnd.nextDouble() * 6;          // 24-30 fps
        int uptime = 100000 + rnd.nextInt(900000);       // ~1-10 days in seconds
        return String.format("""
                {
                  "cameraId": "%s",
                  "siteId": "%s",
                  "status": "online",
                  "cpuPercent": %.1f,
                  "temperatureCelsius": %.1f,
                  "uptimeSeconds": %d,
                  "fps": %.2f,
                  "timestamp": "%s"
                }""", cameraId, siteId, cpu, temp, uptime, fps, Instant.now());
    }

    /** Generates a motion-detection alert JSON payload with a random zone and confidence score. */
    public static String alertPayload(String siteId, String cameraId) {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double confidence = 0.7 + rnd.nextDouble() * 0.3; // 70-100%
        String[] zones = {"entrance-north", "entrance-south", "loading-dock", "parking-lot", "hallway-main"};
        String zone = zones[rnd.nextInt(zones.length)];
        return String.format("""
                {
                  "cameraId": "%s",
                  "siteId": "%s",
                  "alertType": "motion_detected",
                  "confidence": %.2f,
                  "zone": "%s",
                  "thumbnailRef": "s3://sentinel-frames/%s/%s/%d.jpg",
                  "timestamp": "%s"
                }""", cameraId, siteId, confidence, zone,
                LocalDate.now(), cameraId, System.currentTimeMillis(), Instant.now());
    }

    /** Generates a command acknowledgement JSON payload (response to a cloud-issued command). */
    public static String commandAckPayload(String cameraId, String requestId) {
        int execTime = 100 + ThreadLocalRandom.current().nextInt(400); // 100-500ms simulated execution
        return String.format("""
                {
                  "requestId": "%s",
                  "cameraId": "%s",
                  "status": "success",
                  "executionTimeMs": %d
                }""", requestId, cameraId, execTime);
    }

    private SentinelViewProtocol() {}
}
