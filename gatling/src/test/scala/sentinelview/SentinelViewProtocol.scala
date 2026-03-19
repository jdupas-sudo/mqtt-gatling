package sentinelview

import io.gatling.core.Predef._
import io.gatling.mqtt.Predef._

object SentinelViewProtocol {

  val brokerHost: String = System.getProperty("gatling.broker.host", "localhost")
  val brokerPort: Int    = System.getProperty("gatling.broker.port", "1883").toInt
  val users: Int         = System.getProperty("gatling.users", "500").toInt
  val durationSec: Int   = System.getProperty("gatling.duration", "300").toInt

  val siteIds: Seq[String] = (1 to 20).map(i => f"site-retail-$i%03d")

  val mqttProtocol = mqtt
    .mqttVersion_5
    .broker(brokerHost, brokerPort)
    .clientId("sv-cam-#{userId}")
    .cleanSession(true)
    .reconnectAttemptsMax(3)
    .reconnectDelay(1)
    .reconnectBackoffMultiplier(1.5f)

  val cameraFeeder = Iterator.continually {
    val siteId = siteIds(scala.util.Random.nextInt(siteIds.length))
    val camIdx = scala.util.Random.nextInt(10000)
    Map(
      "siteId"   -> siteId,
      "cameraId" -> f"cam-$camIdx%04d",
      "userId"   -> s"$siteId-cam-$camIdx%04d"
    )
  }

  def telemetryPayload(siteId: String, cameraId: String): String = {
    val cpu  = 20 + scala.util.Random.nextDouble() * 60
    val temp = 30 + scala.util.Random.nextDouble() * 25
    val fps  = 24 + scala.util.Random.nextDouble() * 6
    val uptime = 100000 + scala.util.Random.nextInt(900000)
    s"""{
       |  "cameraId": "$cameraId",
       |  "siteId": "$siteId",
       |  "status": "online",
       |  "cpuPercent": ${f"$cpu%.1f"},
       |  "temperatureCelsius": ${f"$temp%.1f"},
       |  "uptimeSeconds": $uptime,
       |  "fps": ${f"$fps%.2f"},
       |  "timestamp": "${java.time.Instant.now()}"
       |}""".stripMargin
  }

  def alertPayload(siteId: String, cameraId: String): String = {
    val confidence = 0.7 + scala.util.Random.nextDouble() * 0.3
    val zones = Seq("entrance-north", "entrance-south", "loading-dock", "parking-lot", "hallway-main")
    val zone = zones(scala.util.Random.nextInt(zones.length))
    s"""{
       |  "cameraId": "$cameraId",
       |  "siteId": "$siteId",
       |  "alertType": "motion_detected",
       |  "confidence": ${f"$confidence%.2f"},
       |  "zone": "$zone",
       |  "thumbnailRef": "s3://sentinel-frames/${java.time.LocalDate.now()}/$cameraId/${System.currentTimeMillis()}.jpg",
       |  "timestamp": "${java.time.Instant.now()}"
       |}""".stripMargin
  }

  def commandAckPayload(cameraId: String, requestId: String): String = {
    val execTime = 100 + scala.util.Random.nextInt(400)
    s"""{
       |  "requestId": "$requestId",
       |  "cameraId": "$cameraId",
       |  "status": "success",
       |  "executionTimeMs": $execTime
       |}""".stripMargin
  }
}
