package sentinelview;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.mqtt.MqttDsl.*;
import static sentinelview.SentinelViewProtocol.*;

/**
 * Baseline publish-only load test.
 *
 * Each virtual camera connects once and publishes telemetry every 5 seconds
 * for the entire test duration. No subscriptions, no bidirectional traffic.
 * Use this to measure raw broker throughput for high-frequency, low-payload MQTT publishes.
 *
 * Injection: all users ramped in over the first half of the duration, then hold.
 * maxDuration ensures the test stops cleanly even if the during() loop overshoots.
 */
public class HeartbeatFloodSimulation extends Simulation {

    ScenarioBuilder scn = scenario("Heartbeat Flood")
            .feed(cameraFeeder())                                  // assign random site + camera identity
            .exec(mqtt("connect").connect())                       // open MQTT connection
            .during(Duration.ofSeconds(DURATION_SEC)).on(          // loop for the configured duration
                    pace(Duration.ofSeconds(5))                    // one iteration every 5s (throttled)
                            .exec(session -> {
                                // build a fresh telemetry payload with randomized metrics
                                String siteId = session.getString("siteId");
                                String cameraId = session.getString("cameraId");
                                return session.set("telemetryPayload", telemetryPayload(siteId, cameraId));
                            })
                            .exec(mqtt("publish telemetry")
                                    .publish("sentinelview/#{siteId}/#{cameraId}/telemetry")
                                    .message(StringBody("#{telemetryPayload}"))
                                    .qosAtMostOnce())              // QoS 0: fire-and-forget (telemetry)
            );

    {
        setUp(
                scn.injectOpen(rampUsers(USERS).during(Duration.ofSeconds(DURATION_SEC / 2)))
        ).protocols(MQTT_PROTOCOL)
                .maxDuration(Duration.ofSeconds(DURATION_SEC))     // hard stop — ensures test terminates
                .assertions(
                        global().responseTime().mean().lt(50),     // mean MQTT publish time < 50ms
                        global().successfulRequests().percent().gt(99.5)
                );
    }
}
