package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants

class BhFitnessIndoorBike(deviceName: String) :
    FtmsDevice(deviceName, FtmsConstants.MachineType.INDOOR_BIKE) {
    // BH Fitness indoor bikes use standard FTMS protocol.
    // This class is prepared for vendor-specific quirks if discovered.
}
