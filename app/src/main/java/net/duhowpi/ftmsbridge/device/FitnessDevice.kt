package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsCapabilities
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample

interface FitnessDevice {
    val deviceName: String
    val machineType: FtmsConstants.MachineType
    val capabilities: FtmsCapabilities?

    fun onFeaturesReceived(data: ByteArray)
    fun onDataReceived(data: ByteArray): FitnessSample?

    /** Called when a BH Fitness iConcept proprietary notification arrives. */
    fun onIConceptData(data: ByteArray) {}

    companion object {
        fun isBhFitness(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("bh") ||
                    lower.contains("i.concept") ||
                    lower.contains("bhfitness") ||
                    lower.contains("bh fitness")
        }
    }
}
