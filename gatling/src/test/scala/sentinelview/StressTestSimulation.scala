package sentinelview

import scala.concurrent.duration._
import io.gatling.core.Predef._
import io.gatling.mqtt.Predef._

class StressTestSimulation extends Simulation {

  import SentinelViewProtocol._

  // Each user connects, publishes telemetry in a loop until the global maxDuration kills the test.
  // The injection profile ramps in stages so we can observe when the broker starts to degrade.
  val scn = scenario("Stress Test")
    .feed(cameraFeeder)
    .exec(mqtt("connect").connect)
    .forever {
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
        .exec(
          mqtt("publish alert")
            .publish("sentinelview/#{siteId}/#{cameraId}/alert")
            .message(StringBody("#{alertPayload}"))
            .qosAtLeastOnce
        )
    }

  // Staircase injection: ramp to each stage over 20s, hold for 40s, then next stage.
  // Stages: users*1, users*2, users*4, users*8, users*16
  // With default users=500: 500 → 1000 → 2000 → 4000 → 8000
  // With users=50 (for testing): 50 → 100 → 200 → 400 → 800
  val stageDuration = (durationSec / 5).seconds

  setUp(
    scn.inject(
      rampUsers(users).during(stageDuration),
      rampUsers(users).during(stageDuration),
      rampUsers(users * 2).during(stageDuration),
      rampUsers(users * 4).during(stageDuration),
      rampUsers(users * 8).during(stageDuration)
    )
  ).protocols(mqttProtocol)
    .maxDuration(durationSec.seconds)
}
