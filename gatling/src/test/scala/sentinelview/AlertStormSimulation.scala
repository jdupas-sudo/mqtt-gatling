package sentinelview

import scala.concurrent.duration._
import io.gatling.core.Predef._
import io.gatling.mqtt.Predef._

class AlertStormSimulation extends Simulation {

  import SentinelViewProtocol._

  val scn = scenario("Alert Storm")
    .feed(cameraFeeder)
    .exec(mqtt("connect").connect)
    // Subscribe to command topic for this camera
    .exec(
      mqtt("subscribe command")
        .subscribe("sentinelview/#{siteId}/#{cameraId}/command")
        .qosAtLeastOnce
    )
    .during(durationSec.seconds) {
      // Publish telemetry every 5 seconds
      pace(5.seconds)
        .exec { session =>
          val siteId   = session("siteId").as[String]
          val cameraId = session("cameraId").as[String]
          session
            .set("telemetryPayload", telemetryPayload(siteId, cameraId))
            .set("alertPayload", alertPayload(siteId, cameraId))
        }
        .exec(
          mqtt("publish telemetry")
            .publish("sentinelview/#{siteId}/#{cameraId}/telemetry")
            .message(StringBody("#{telemetryPayload}"))
            .qosAtMostOnce
        )
        // Publish alert with random interval (simulated by probabilistic firing)
        .randomSwitch(
          60.0 -> exec(
            mqtt("publish alert")
              .publish("sentinelview/#{siteId}/#{cameraId}/alert")
              .message(StringBody("#{alertPayload}"))
              .qosAtLeastOnce
          )
        )
    }

  setUp(
    scn.inject(
      rampUsers(users).during((durationSec / 2).seconds)
    )
  ).protocols(mqttProtocol)
    .maxDuration(durationSec.seconds)
    .assertions(
      global.responseTime.percentile(99.0).lt(200),
      global.successfulRequests.percent.gt(99.0)
    )
}
