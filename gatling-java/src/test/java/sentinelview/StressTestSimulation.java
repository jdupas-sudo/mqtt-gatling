package sentinelview;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.mqtt.MqttDsl.*;
import static sentinelview.SentinelViewProtocol.*;

/**
 * Staircase stress test — progressively doubles concurrent connections to find the breaking point.
 *
 * Each stage ramps in new users while all previous users stay connected and keep publishing.
 * The duration is split into 5 equal stages:
 *
 *   Stage 1: +users×1  → total: users×1
 *   Stage 2: +users×1  → total: users×2
 *   Stage 3: +users×2  → total: users×4
 *   Stage 4: +users×4  → total: users×8
 *   Stage 5: +users×8  → total: users×16
 *
 * Example with users=50, duration=300 (60s per stage):
 *   50 → 100 → 200 → 400 → 800 concurrent cameras at peak
 *
 * Example with users=200, duration=300:
 *   200 → 400 → 800 → 1600 → 3200 concurrent cameras at peak
 *
 * Look for the inflection point in Gatling Enterprise reports where response times
 * spike or error rates climb — that's the broker's capacity limit.
 *
 * All users loop forever (publish telemetry + alert every 5s) until the test ends.
 * maxDuration ensures a clean stop.
 */
public class StressTestSimulation extends Simulation {

    ScenarioBuilder scn = scenario("Stress Test")
            .feed(cameraFeeder())
            .exec(mqtt("connect").connect())
            .forever().on(                                         // loop until maxDuration kills the test
                    pace(Duration.ofSeconds(5))
                            .exec(session -> {
                                String siteId = session.getString("siteId");
                                String cameraId = session.getString("cameraId");
                                return session
                                        .set("telemetryPayload", telemetryPayload(siteId, cameraId))
                                        .set("alertPayload", alertPayload(siteId, cameraId));
                            })
                            .exec(mqtt("publish telemetry")
                                    .publish("sentinelview/#{siteId}/#{cameraId}/telemetry")
                                    .message(StringBody("#{telemetryPayload}"))
                                    .qosAtMostOnce())
                            .exec(mqtt("publish alert")
                                    .publish("sentinelview/#{siteId}/#{cameraId}/alert")
                                    .message(StringBody("#{alertPayload}"))
                                    .qosAtLeastOnce())
            );

    /** Each stage lasts 1/5th of the total duration */
    Duration stageDuration = Duration.ofSeconds(DURATION_SEC / 5);

    {
        // Staircase injection: each stage adds more users, doubling cumulative total
        setUp(
                scn.injectOpen(
                        rampUsers(USERS).during(stageDuration),          // Stage 1: +1x
                        rampUsers(USERS).during(stageDuration),          // Stage 2: +1x (total 2x)
                        rampUsers(USERS * 2).during(stageDuration),      // Stage 3: +2x (total 4x)
                        rampUsers(USERS * 4).during(stageDuration),      // Stage 4: +4x (total 8x)
                        rampUsers(USERS * 8).during(stageDuration)       // Stage 5: +8x (total 16x)
                )
        ).protocols(MQTT_PROTOCOL)
                .maxDuration(Duration.ofSeconds(DURATION_SEC));          // hard stop
    }
}
