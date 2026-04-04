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
        // iConcept / BH Fitness devices use a device-name pattern of the form
        // <letter><two digits>_<5 hex chars>, e.g. "B01_479D7" (indoor bike) or
        // "C01_12DB5" (vertical bike).  This pattern is used in addition to the
        // human-readable brand/product name matches below.
        private val iConceptNamePattern = Regex("^[a-z]\\d{2}_[0-9a-f]{5}$")

        fun isBhFitness(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("bh") ||
                    lower.contains("i.concept") ||
                    lower.contains("bhfitness") ||
                    lower.contains("bh fitness") ||
                    iConceptNamePattern.matches(lower)
        }
    }
}
