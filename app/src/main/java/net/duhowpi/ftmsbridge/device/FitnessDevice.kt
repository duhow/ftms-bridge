package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsCapabilities
import net.duhowpi.ftmsbridge.ftms.FtmsConstants
import net.duhowpi.ftmsbridge.model.FitnessSample
import kotlin.math.roundToInt

interface FitnessDevice {
    val deviceName: String
    val machineType: FtmsConstants.MachineType
    val capabilities: FtmsCapabilities?

    fun onFeaturesReceived(data: ByteArray)
    fun onDataReceived(data: ByteArray): FitnessSample?

    /** Called when a BH Fitness iConcept proprietary notification arrives. */
    fun onIConceptData(data: ByteArray) {}

    /**
     * Encodes a physical inclination percentage into the raw SINT16 value to send in a
     * Fitness Machine Control Point Set Target Inclination command (opcode 0x03).
     *
     * Default: standard FTMS encoding — SINT16 in units of 0.1 %.
     * Devices with non-standard scaling (e.g. BH Fitness) override this.
     */
    fun encodeTargetInclineRaw(physicalPercent: Double): Int =
        (physicalPercent * 10.0).roundToInt()

    /**
     * Resets internal elapsed-time accumulators so that the derived elapsed time
     * reported in subsequent samples starts from zero.  Called when a new recording
     * session begins so the in-app timer is anchored to the activity start, not to
     * the BLE connection time.
     *
     * Default implementation is a no-op; devices that derive elapsed time internally
     * (e.g. BH Fitness treadmill) should override this.
     */
    fun resetElapsedTime() {}

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
