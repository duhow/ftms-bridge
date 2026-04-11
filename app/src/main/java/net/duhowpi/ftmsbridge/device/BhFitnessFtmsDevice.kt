package net.duhowpi.ftmsbridge.device

import net.duhowpi.ftmsbridge.ftms.FtmsConstants

/**
 * Abstract base class for all BH Fitness FTMS devices.
 *
 * Centralises BH Fitness / iConcept-specific detection logic and shared physical
 * constants so that concrete device classes ([BhFitnessTreadmill],
 * [BhFitnessIndoorBike], [BhFitnessVerticalBike]) do not need to redeclare them.
 */
abstract class BhFitnessFtmsDevice(
    deviceName: String,
    machineType: FtmsConstants.MachineType
) : FtmsDevice(deviceName, machineType) {

    companion object {
        // ── Physical constants ──────────────────────────────────────────────────

        /** km/h → m/s conversion factor (1 km/h = 1/3.6 m/s). */
        const val METERS_PER_KMH_PER_SEC = 1.0 / 3.6

        /** Energy conversion factor: 1 kcal = 4184 J. */
        const val JOULES_PER_KCAL = 4184.0

        // ── BH Fitness / iConcept device-name detection ─────────────────────────
        //
        // iConcept devices advertise a short-code name of the form
        // <letter><two digits>_<5 hex chars>, e.g. "B01_479D7" (indoor bike) or
        // "C01_12DB5" (vertical bike).  This is checked in addition to the
        // human-readable brand/product name patterns below.
        private val iConceptShortNamePattern = Regex(
            "^[a-z]\\d{2}_[0-9a-f]{5}$",
            RegexOption.IGNORE_CASE
        )

        /**
         * Returns a [Regex] that matches any BH Fitness or iConcept device name.
         *
         * The pattern covers all known name variants:
         * - Names starting with "BH" (e.g. "BH Fitness T01")
         * - Names containing "bhfitness" or "bh fitness"
         * - Names containing "i.concept"
         * - iConcept short-code names (e.g. "B01_479D7", "C01_12DB5")
         *
         * Usage: `getSupportedDeviceName().containsMatchIn(advertisedName)`
         */
        fun getSupportedDeviceName(): Regex = Regex(
            "(?:^bh|bhfitness|bh fitness|i\\.concept|^[a-z]\\d{2}_[0-9a-f]{5}$)",
            RegexOption.IGNORE_CASE
        )

        /**
         * Returns `true` if the advertised [name] (and optional [macAddress]) belong
         * to a BH Fitness or iConcept device.
         *
         * TODO: MAC address prefix matching (e.g. known BH Fitness OUI prefixes)
         *   is not yet implemented; currently only [name] is evaluated.
         */
        fun matchesDevice(name: String, macAddress: String? = null): Boolean =
            isBhFitness(name)

        /**
         * Returns `true` if [name] identifies a BH Fitness or iConcept device.
         *
         * Checks for known brand/product name substrings as well as the iConcept
         * short-code name format.
         */
        fun isBhFitness(name: String): Boolean {
            val lower = name.lowercase()
            return lower.startsWith("bh") ||
                    lower.contains("i.concept") ||
                    lower.contains("bhfitness") ||
                    lower.contains("bh fitness") ||
                    iConceptShortNamePattern.matches(lower)
        }
    }
}
