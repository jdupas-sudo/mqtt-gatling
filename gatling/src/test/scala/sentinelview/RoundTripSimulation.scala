package sentinelview

import scala.concurrent.duration._
import io.gatling.core.Predef._
import io.gatling.mqtt.Predef._

/**
 * End-to-end round-trip validation.
 *
 * Each camera:
 * 1. Connects and subscribes to its command topic
 * 2. Publishes an alert (QoS 1)
 * 3. Waits (via .expect) for the command-server to respond with a command
 *    on `sentinelview/{siteId}/{cameraId}/command` within a timeout
 *
 * The command-server is configured to immediately reply to every alert
 * with a command back to the originating camera (ALERT_RESPONSE_ENABLED=true).
 *
 * This measures the full round-trip latency:
 *   Camera → EMQX → command-server → EMQX → Camera
 *
 * Use fewer users here (e.g. 50) — the goal is latency measurement, not volume.
 */
class RoundTripSimulation extends Simulation {

  import SentinelViewProtocol._

  val scn = scenario("Round Trip")
    .feed(cameraFeeder)
    .exec(mqtt("connect").connect)
    // Subscribe to this camera's command topic and wait for the subscription to be confirmed
    .exec(
      mqtt("subscribe command")
        .subscribe("sentinelview/#{siteId}/#{cameraId}/command")
        .qosAtLeastOnce
    )
    .during(durationSec.seconds) {
      pace(5.seconds)
        .exec { session =>
          val siteId   = session("siteId").as[String]
          val cameraId = session("cameraId").as[String]
          session.set("alertPayload", alertPayload(siteId, cameraId))
        }
        // Publish alert and expect a command back within 5 seconds.
        // The command-server receives the alert and immediately publishes
        // a command to this camera's command topic.
        .exec(
          mqtt("publish alert, expect command")
            .publish("sentinelview/#{siteId}/#{cameraId}/alert")
            .message(StringBody("#{alertPayload}"))
            .qosAtLeastOnce
            .expect(5.seconds)
            .check(jsonPath("$.commandType").exists)
        )
    }

  setUp(
    scn.inject(
      rampUsers(users).during((durationSec / 2).seconds)
    )
  ).protocols(mqttProtocol)
    .maxDuration(durationSec.seconds)
    .assertions(
      // Round-trip p99 should stay under 2 seconds
      global.responseTime.percentile(99.0).lt(2000),
      global.successfulRequests.percent.gt(95.0)
    )
}
