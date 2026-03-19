class TelemetryLogger {
  constructor() {
    this.activeCameras = new Map();
    this.alertCount = 0;
    this.commandAckCount = 0;
    this.commandAckSuccessCount = 0;
    this.cameraExpirySec = 30;
  }

  handleTelemetry(topic, payload) {
    try {
      const data = JSON.parse(payload.toString());
      const key = `${data.siteId}/${data.cameraId}`;
      this.activeCameras.set(key, {
        lastSeen: Date.now(),
        status: data.status,
        siteId: data.siteId,
        cameraId: data.cameraId,
      });
    } catch {
      // ignore malformed payloads
    }
  }

  handleAlert(topic, payload) {
    this.alertCount++;
  }

  handleCommandAck(topic, payload) {
    this.commandAckCount++;
    try {
      const data = JSON.parse(payload.toString());
      if (data.status === "success") {
        this.commandAckSuccessCount++;
      }
    } catch {
      // ignore malformed payloads
    }
  }

  getActiveCameraKeys() {
    const now = Date.now();
    const expiry = this.cameraExpirySec * 1000;
    const active = [];
    for (const [key, info] of this.activeCameras) {
      if (now - info.lastSeen < expiry) {
        active.push(key);
      } else {
        this.activeCameras.delete(key);
      }
    }
    return active;
  }

  getMetrics() {
    const activeCameras = this.getActiveCameraKeys();
    return {
      activeCameraCount: activeCameras.length,
      totalAlertsReceived: this.alertCount,
      commandAcksReceived: this.commandAckCount,
      commandAckSuccessRate:
        this.commandAckCount > 0
          ? (this.commandAckSuccessCount / this.commandAckCount).toFixed(4)
          : "N/A",
    };
  }
}

module.exports = TelemetryLogger;
