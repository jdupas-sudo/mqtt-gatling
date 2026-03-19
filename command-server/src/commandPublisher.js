const { v4: uuidv4 } = require("uuid");

const COMMAND_TYPES = [
  {
    commandType: "set_config",
    params: { nightMode: true, fps: 15 },
  },
  {
    commandType: "ptz_move",
    params: { pan: 45, tilt: -10, zoom: 2.0 },
  },
  {
    commandType: "reboot",
    params: { delay: 5 },
  },
  {
    commandType: "update_firmware",
    params: { version: "2.4.1", url: "https://fw.sentinelview.io/2.4.1.bin" },
  },
];

class CommandPublisher {
  constructor(mqttClient, telemetryLogger, intervalMs) {
    this.mqttClient = mqttClient;
    this.telemetryLogger = telemetryLogger;
    this.intervalMs = intervalMs;
    this.commandsSent = 0;
    this.timer = null;
  }

  start() {
    this.timer = setInterval(() => this.publishCommand(), this.intervalMs);
    console.log(
      `[CommandPublisher] Publishing commands every ${this.intervalMs}ms`
    );
  }

  stop() {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  publishCommand() {
    const activeCameras = this.telemetryLogger.getActiveCameraKeys();
    if (activeCameras.length === 0) {
      return;
    }

    const randomKey =
      activeCameras[Math.floor(Math.random() * activeCameras.length)];
    const [siteId, cameraId] = randomKey.split("/");
    this.publishCommandTo(siteId, cameraId);
  }

  // Send a command to a specific camera. Used by the periodic publisher
  // and by the alert-response handler for round-trip testing.
  publishCommandTo(siteId, cameraId) {
    const template =
      COMMAND_TYPES[Math.floor(Math.random() * COMMAND_TYPES.length)];

    const payload = {
      requestId: `req-${uuidv4().slice(0, 8)}`,
      commandType: template.commandType,
      params: { ...template.params },
      issuedAt: new Date().toISOString(),
    };

    const topic = `sentinelview/${siteId}/${cameraId}/command`;
    this.mqttClient.publish(topic, JSON.stringify(payload), { qos: 1 });
    this.commandsSent++;
  }

  getStats() {
    return { commandsSent: this.commandsSent };
  }
}

module.exports = CommandPublisher;
