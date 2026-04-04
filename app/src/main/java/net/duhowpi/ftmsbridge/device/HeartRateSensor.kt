package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsDataParser

class HeartRateSensor(val sensorName: String) {
    var lastHeartRate: Int = 0
        private set

    fun onDataReceived(data: ByteArray): Int {
        lastHeartRate = FtmsDataParser.parseHeartRate(data)
        return lastHeartRate
    }
}
