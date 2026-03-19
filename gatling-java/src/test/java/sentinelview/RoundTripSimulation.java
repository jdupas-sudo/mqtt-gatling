package sentinelview;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.mqtt.MqttDsl.*;
import static sentinelview.SentinelViewProtocol.*;

/**
 * End-to-end round-trip validation.
 *
 * Each camera:
 * 1. Connects and subscribes to its command topic
 * 2. Publishes an alert (QoS 1)
 * 3. Waits (via .expect) for the command-server to respond with a command
 *    on sentinelview/{siteId}/{cameraId}/command within a timeout
 *
 * The command-server is configured to immediately reply to every alert
 * with a command back to the originating camera (ALERT_RESPONSE_ENABLED=true).
 *
 * This measures the full round-trip latency:
 *   Camera → EMQX → command-server → EMQX → Camera
 *
 * Use fewer users here (e.g. 50) — the goal is latency measurement, not volume.
 */
public class RoundTripSimulation extends Simulation {

    ScenarioBuilder scn = scenario("Round Trip")
            .feed(cameraFeeder())
            .exec(mqtt("connect").connect())
            // Subscribe to this camera's command topic and wait for confirmation
            .exec(mqtt("subscribe command")
                    .subscribe("sentinelview/#{siteId}/#{cameraId}/command")
                    .qosAtLeastOnce())
            .during(Duration.ofSeconds(DURATION_SEC)).on(
                    pace(Duration.ofSeconds(5))
                            .exec(session -> {
                                String siteId = session.getString("siteId");
                                String cameraId = session.getString("cameraId");
                                return session.set("alertPayload", alertPayload(siteId, cameraId));
                            })
                            // Publish alert and expect a command back within 5 seconds.
                            // The command-server receives the alert and immediately publishes
                            // a command to this camera's command topic.
                            .exec(mqtt("publish alert, expect command")
                                    .publish("sentinelview/#{siteId}/#{cameraId}/alert")
                                    .message(StringBody("#{alertPayload}"))
                                    .qosAtLeastOnce()
                                    .expect(Duration.ofSeconds(5))
                                    .check(jsonPath("$.commandType").exists()))
            );

    {
        setUp(
                scn.injectOpen(rampUsers(USERS).during(Duration.ofSeconds(DURATION_SEC / 2)))
        ).protocols(MQTT_PROTOCOL)
                .maxDuration(Duration.ofSeconds(DURATION_SEC))
                .assertions(
                        // Round-trip p99 should stay under 2 seconds
                        global().responseTime().percentile(99.0).lt(2000),
                        global().successfulRequests().percent().gt(95.0)
                );
    }
}
