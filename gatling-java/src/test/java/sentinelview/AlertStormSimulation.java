package sentinelview;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.mqtt.MqttDsl.*;
import static sentinelview.SentinelViewProtocol.*;

/**
 * Full bidirectional traffic simulation — the main showcase scenario.
 *
 * Each camera:
 * 1. Connects and subscribes to its command topic (QoS 1)
 * 2. Publishes telemetry every 5s (QoS 0)
 * 3. With 60% probability, also publishes a motion-detection alert (QoS 1)
 *
 * The command-server (Node.js) sends commands to cameras via EMQX;
 * cameras that receive them would normally ACK — this simulation focuses
 * on the pub/sub storm rather than command handling.
 *
 * Models a peak event like a warehouse shift change where many cameras
 * trigger motion alerts simultaneously.
 */
public class AlertStormSimulation extends Simulation {

    ScenarioBuilder scn = scenario("Alert Storm")
            .feed(cameraFeeder())
            .exec(mqtt("connect").connect())
            .exec(mqtt("subscribe command")                        // subscribe to cloud commands (QoS 1)
                    .subscribe("sentinelview/#{siteId}/#{cameraId}/command")
                    .qosAtLeastOnce())
            .during(Duration.ofSeconds(DURATION_SEC)).on(
                    pace(Duration.ofSeconds(5))
                            .exec(session -> {
                                String siteId = session.getString("siteId");
                                String cameraId = session.getString("cameraId");
                                return session
                                        .set("telemetryPayload", telemetryPayload(siteId, cameraId))
                                        .set("alertPayload", alertPayload(siteId, cameraId));
                            })
                            .exec(mqtt("publish telemetry")        // always send telemetry
                                    .publish("sentinelview/#{siteId}/#{cameraId}/telemetry")
                                    .message(StringBody("#{telemetryPayload}"))
                                    .qosAtMostOnce())
                            .randomSwitch().on(                    // 60% chance of also sending an alert
                                    percent(60.0).then(
                                            exec(mqtt("publish alert")
                                                    .publish("sentinelview/#{siteId}/#{cameraId}/alert")
                                                    .message(StringBody("#{alertPayload}"))
                                                    .qosAtLeastOnce())   // QoS 1: at-least-once (alerts matter)
                                    )
                            )
            );

    {
        setUp(
                scn.injectOpen(rampUsers(USERS).during(Duration.ofSeconds(DURATION_SEC / 2)))
        ).protocols(MQTT_PROTOCOL)
                .maxDuration(Duration.ofSeconds(DURATION_SEC))
                .assertions(
                        global().responseTime().percentile(99.0).lt(200),  // p99 < 200ms
                        global().successfulRequests().percent().gt(99.0)
                );
    }
}
