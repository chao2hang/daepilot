package io.github.daepilot.core.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CompatibilityCheckerTest {
    @Test
    fun parsesAndroidKernelRelease() {
        assertEquals(6 to 12, CompatibilityChecker.parseKernel("6.12.69-android16-11-gabcdef"))
    }

    @Test
    fun parsesMinimumSupportedKernel() {
        assertEquals(5 to 15, CompatibilityChecker.parseKernel("5.15.0"))
    }

    @Test
    fun parsesOlderKernel() {
        assertEquals(4 to 19, CompatibilityChecker.parseKernel("4.19.191-g1234"))
    }

    @Test
    fun returnsZeroForUnknownRelease() {
        assertEquals(0 to 0, CompatibilityChecker.parseKernel("unknown"))
        assertEquals(0 to 0, CompatibilityChecker.parseKernel(""))
    }

    @Test
    fun noWarningLineMeansNothingReportedYet() {
        assertNull(CompatibilityChecker.pnameRouting(0L, 0L))
        assertNull(CompatibilityChecker.pnameRouting(null, null))
    }

    @Test
    fun warningInLiveGenerationMeansPnameIsDead() {
        // The warning sits after the previous control plane, so it belongs to
        // the one that is running now.
        assertEquals(false, CompatibilityChecker.pnameRouting(80L, 10L))
        assertEquals(false, CompatibilityChecker.pnameRouting(80L, null))
    }

    @Test
    fun warningOnlyFromAnOlderGenerationMeansPnameWorks() {
        assertEquals(true, CompatibilityChecker.pnameRouting(10L, 80L))
    }
}
