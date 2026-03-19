package sentinelview

import scala.concurrent.duration._
import io.gatling.core.Predef._
import io.gatling.mqtt.Predef._

class HeartbeatFloodSimulation extends Simulation {

  import SentinelViewProtocol._

  val scn = scenario("Heartbeat Flood")
    .feed(cameraFeeder)
    .exec(mqtt("connect").connect)
    .during(durationSec.seconds) {
      pace(5.seconds)
        .exec { session =>
          val siteId   = session("siteId").as[String]
          val cameraId = session("cameraId").as[String]
          session.set("telemetryPayload", telemetryPayload(siteId, cameraId))
        }
        .exec(
          mqtt("publish telemetry")
            .publish("sentinelview/#{siteId}/#{cameraId}/telemetry")
            .message(StringBody("#{telemetryPayload}"))
            .qosAtMostOnce
        )
    }

  setUp(
    scn.inject(
      rampUsers(users).during((durationSec / 2).seconds)
    )
  ).protocols(mqttProtocol)
    .maxDuration(durationSec.seconds)
    .assertions(
      global.responseTime.mean.lt(50),
      global.successfulRequests.percent.gt(99.5)
    )
}
