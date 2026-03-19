package sentinelview

import scala.concurrent.duration._
import io.gatling.core.Predef._
import io.gatling.mqtt.Predef._

class ConnectionChurnSimulation extends Simulation {

  import SentinelViewProtocol._

  val scn = scenario("Connection Churn")
    .feed(cameraFeeder)
    .exec(mqtt("connect").connect)
    .repeat(3) {
      exec { session =>
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
      .pause(1.second, 2.seconds)
    }
    .pause(2.seconds, 5.seconds)

  setUp(
    scn.inject(
      constantUsersPerSec(users.toDouble / 10).during(durationSec.seconds)
    )
  ).protocols(mqttProtocol)
    .maxDuration(durationSec.seconds)
    .assertions(
      global.successfulRequests.percent.gt(99.9)
    )
}
