package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants

class BhFitnessTreadmill(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.TREADMILL) {
    // BH Fitness treadmills use standard FTMS protocol.
    // This class is prepared for vendor-specific quirks if discovered.
}
