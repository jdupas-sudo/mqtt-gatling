package sentinelview;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.mqtt.MqttDsl.*;
import static sentinelview.SentinelViewProtocol.*;

/**
 * Resilience test — rapid connect/disconnect cycles.
 *
 * Simulates camera reboots, Wi-Fi drops, and firmware updates where devices
 * connect briefly, send a few messages, then disconnect. This pattern is
 * particularly good at revealing:
 * - Memory leaks in the broker's session management
 * - Slow session cleanup or lingering subscriptions
 * - Connection-tracking overhead
 *
 * Each virtual user connects, publishes 3 telemetry messages with short pauses,
 * then disconnects. New users arrive at a constant rate throughout the test.
 */
public class ConnectionChurnSimulation extends Simulation {

    ScenarioBuilder scn = scenario("Connection Churn")
            .feed(cameraFeeder())
            .exec(mqtt("connect").connect())
            .repeat(3).on(                                         // send 3 messages then disconnect
                    exec(session -> {
                        String siteId = session.getString("siteId");
                        String cameraId = session.getString("cameraId");
                        return session.set("telemetryPayload", telemetryPayload(siteId, cameraId));
                    })
                    .exec(mqtt("publish telemetry")
                            .publish("sentinelview/#{siteId}/#{cameraId}/telemetry")
                            .message(StringBody("#{telemetryPayload}"))
                            .qosAtMostOnce())
                    .pause(Duration.ofSeconds(1), Duration.ofSeconds(2))  // brief pause between messages
            )
            .pause(Duration.ofSeconds(2), Duration.ofSeconds(5));  // pause before implicit disconnect

    {
        setUp(
                // steady stream of short-lived connections (USERS/10 per second)
                scn.injectOpen(constantUsersPerSec((double) USERS / 10).during(Duration.ofSeconds(DURATION_SEC)))
        ).protocols(MQTT_PROTOCOL)
                .maxDuration(Duration.ofSeconds(DURATION_SEC))
                .assertions(
                        global().successfulRequests().percent().gt(99.9)
                );
    }
}
