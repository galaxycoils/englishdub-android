package app.englishdub.engine

import app.englishdub.data.DubJob
import org.junit.Assert.*
import org.junit.Test

class HardwareProfileTest {
    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024

    @Test fun `tier classification handles budget balanced high performance and boundary conditions`() {
        // Budget: <= 4 GiB RAM, <= 4 cores, low-RAM flag, or non-ARM64 ABI
        assertEquals(HardwareTier.BUDGET, HardwareProfile.classifyTier(0L, 0))
        assertEquals(HardwareTier.BUDGET, HardwareProfile.classifyTier(-100L, -2))
        assertEquals(HardwareTier.BUDGET, HardwareProfile.classifyTier(3 * gib, 8, "arm64-v8a"))
        assertEquals(HardwareTier.BUDGET, HardwareProfile.classifyTier(4 * gib, 8, "arm64-v8a"))
        assertEquals(HardwareTier.BUDGET, HardwareProfile.classifyTier(8 * gib, 4, "arm64-v8a"))
        assertEquals(HardwareTier.BUDGET, HardwareProfile.classifyTier(8 * gib, 8, "armeabi-v7a"))
        assertEquals(HardwareTier.BUDGET, HardwareProfile.classifyTier(8 * gib, 8, "x86_64"))
        assertEquals(HardwareTier.BUDGET, HardwareProfile.classifyTier(8 * gib, 8, "arm64-v8a", isLowRamDevice = true))

        // Balanced: > 4 GiB and < 7 GiB with >= 6 cores, or >= 7 GiB with 6 cores
        assertEquals(HardwareTier.BALANCED, HardwareProfile.classifyTier(6 * gib, 8, "arm64-v8a"))
        assertEquals(HardwareTier.BALANCED, HardwareProfile.classifyTier((5.6 * gib).toLong(), 6, "arm64-v8a"))
        assertEquals(HardwareTier.BALANCED, HardwareProfile.classifyTier(8 * gib, 6, "arm64-v8a"))

        // High Performance: >= 7 GiB (including 8 GB phones with kernel carveout) and >= 8 cores on ARM64
        assertEquals(HardwareTier.HIGH_PERFORMANCE, HardwareProfile.classifyTier((7.5 * gib).toLong(), 8, "arm64-v8a"))
        assertEquals(HardwareTier.HIGH_PERFORMANCE, HardwareProfile.classifyTier(8 * gib, 8, "arm64-v8a"))
        assertEquals(HardwareTier.HIGH_PERFORMANCE, HardwareProfile.classifyTier(12 * gib, 12, "aarch64"))
    }

    @Test fun `recommended threads and speech cache limits scale with hardware tier and free storage`() {
        val budget = HardwareProfile.evaluate(
            totalRamBytes = 4 * gib,
            availableCores = 4,
            freeStorageBytes = 2 * gib,
            primaryAbi = "arm64-v8a",
            hasHardwareAacEncoder = false,
        )
        assertEquals(HardwareTier.BUDGET, budget.tier)
        assertEquals(1, budget.recommendedThreads)
        assertEquals(128 * mib, budget.recommendedSpeechCacheBytes)
        assertEquals("Detected: 4-core ARM64 · 4 GB RAM · Standard AAC", budget.summaryLine)
        assertEquals("Budget / Low RAM · 1 recognition thread · 128 MB speech cache", budget.tuningSummaryLine)

        val balanced = HardwareProfile.evaluate(
            totalRamBytes = 6 * gib,
            availableCores = 8,
            freeStorageBytes = 5 * gib,
            primaryAbi = "arm64-v8a",
            hasHardwareAacEncoder = true,
        )
        assertEquals(HardwareTier.BALANCED, balanced.tier)
        assertEquals(2, balanced.recommendedThreads)
        assertEquals(256 * mib, balanced.recommendedSpeechCacheBytes)
        assertEquals("Detected: 8-core ARM64 · 6 GB RAM · High-speed AAC", balanced.summaryLine)
        assertEquals("Balanced / Standard · 2 recognition threads · 256 MB speech cache", balanced.tuningSummaryLine)

        val highPerf = HardwareProfile.evaluate(
            totalRamBytes = 12 * gib,
            availableCores = 12,
            freeStorageBytes = 10 * gib,
            primaryAbi = "arm64-v8a",
            hasHardwareAacEncoder = true,
        )
        assertEquals(HardwareTier.HIGH_PERFORMANCE, highPerf.tier)
        assertEquals(4, highPerf.recommendedThreads) // capped at 4 to prevent thermal throttling
        assertEquals(384 * mib, highPerf.recommendedSpeechCacheBytes)
        assertEquals("Detected: 12-core ARM64 · 12 GB RAM · High-speed AAC", highPerf.summaryLine)

        // Low storage (< 1 GiB free) shrinks speech cache cap to 64 MiB across all tiers
        val tightHighPerf = HardwareProfile.evaluate(
            totalRamBytes = 8 * gib,
            availableCores = 8,
            freeStorageBytes = 500 * mib,
            primaryAbi = "arm64-v8a",
        )
        assertEquals(64 * mib, tightHighPerf.recommendedSpeechCacheBytes)
    }

    @Test fun `codec capability inspection detects hardware and platform ARM64 acceleration`() {
        val hwAac = CodecDescriptor("c2.qti.aac.encoder", isEncoder = true, listOf("audio/mp4a-latm"), isHardwareAccelerated = true)
        val swAac = CodecDescriptor("c2.android.aac.encoder", isEncoder = true, listOf("audio/mp4a-latm"), isHardwareAccelerated = false)
        val hwAvc = CodecDescriptor("c2.qti.avc.decoder", isEncoder = false, listOf("video/avc"), isHardwareAccelerated = true)
        val swAvc = CodecDescriptor("c2.android.avc.decoder", isEncoder = false, listOf("video/avc"), isHardwareAccelerated = false)

        assertTrue(HardwareProfile.hasFastAacSupport(listOf(hwAac), isArm64 = false))
        assertTrue(HardwareProfile.hasFastAacSupport(listOf(swAac, hwAvc), isArm64 = true))
        assertFalse(HardwareProfile.hasFastAacSupport(listOf(swAac, hwAvc), isArm64 = false))
        assertFalse(HardwareProfile.hasFastAacSupport(listOf(swAac, swAvc), isArm64 = true))
        assertFalse(HardwareProfile.hasFastAacSupport(listOf(hwAvc), isArm64 = true))
        assertTrue(HardwareProfile.hasHardwareVideoSupport(listOf(hwAvc)))
        assertFalse(HardwareProfile.hasHardwareVideoSupport(listOf(swAvc)))
    }

    @Test fun `setup plan selects speech detector and translation packs based on storage headroom and library films`() {
        val normalProfile = HardwareProfile.evaluate(
            totalRamBytes = 6 * gib,
            availableCores = 8,
            freeStorageBytes = 4 * gib,
        )
        val libraryJobs = listOf(
            DubJob("j1", "Hindi Film", "file:///hindi.mp4", sourceLanguage = "hi", mode = "subtitles"),
            DubJob("j2", "Tamil Film", "file:///tamil.mp4", sourceLanguage = "ta", mode = "subtitles"),
        )
        val plan = HardwareProfile.buildSetupPlan(
            profile = normalProfile,
            speechReady = false,
            vadReady = false,
            installedTranslations = setOf("ta"),
            jobs = libraryJobs,
        )
        assertTrue(plan.downloadSpeechPack)
        assertTrue(plan.downloadVadPack)
        assertEquals(listOf("hi"), plan.translationPacksToDownload)
        assertFalse(plan.allInstalled)
        assertEquals(
            ModelStore.SIZE + ModelStore.VAD_SIZE + HardwareProfile.TRANSLATION_PACK_ESTIMATE_BYTES,
            plan.requiredDownloadBytes,
        )

        // When everything is already installed
        val readyPlan = HardwareProfile.buildSetupPlan(
            profile = normalProfile,
            speechReady = true,
            vadReady = true,
            installedTranslations = setOf("hi", "ta"),
            jobs = libraryJobs,
        )
        assertTrue(readyPlan.allInstalled)
        assertFalse(readyPlan.downloadSpeechPack)
        assertFalse(readyPlan.downloadVadPack)
        assertTrue(readyPlan.translationPacksToDownload.isEmpty())
        assertEquals(0L, readyPlan.requiredDownloadBytes)
    }

    @Test fun `tight storage skips optional 57 MB speech pack unless a film requires speech recognition`() {
        val tightProfile = HardwareProfile.evaluate(
            totalRamBytes = 4 * gib,
            availableCores = 4,
            freeStorageBytes = 200 * mib, // between 129 MiB and 300 MiB
        )
        val noSpeechPlan = HardwareProfile.buildSetupPlan(
            profile = tightProfile,
            speechReady = false,
            vadReady = false,
            installedTranslations = emptySet(),
            jobs = emptyList(),
        )
        assertFalse("Optional 57 MB speech pack should be skipped when storage is tight", noSpeechPlan.downloadSpeechPack)
        assertTrue("Lightweight 0.9 MB detector should still be installed", noSpeechPlan.downloadVadPack)
        assertTrue(HardwareProfile.checkAdmission(tightProfile.freeStorageBytes, noSpeechPlan.requiredDownloadBytes).allowed)

        // If a film in the library explicitly uses speech mode, speech pack is required
        val speechJob = DubJob("j-speech", "No Subs Film", "file:///nosubs.mp4", sourceLanguage = "hi", mode = "speech")
        val speechRequiredPlan = HardwareProfile.buildSetupPlan(
            profile = tightProfile,
            speechReady = false,
            vadReady = false,
            installedTranslations = emptySet(),
            jobs = listOf(speechJob),
        )
        assertTrue(speechRequiredPlan.downloadSpeechPack)
        assertTrue(speechRequiredPlan.downloadVadPack)
    }

    @Test fun `admission check enforces thermal battery and storage headroom rules`() {
        // Thermal severe blocks setup
        val thermalBlocked = HardwareProfile.checkAdmission(
            freeStorageBytes = 2 * gib,
            requiredDownloadBytes = 60 * mib,
            batteryPercent = 80,
            isCharging = true,
            thermalSevere = true,
        )
        assertFalse(thermalBlocked.allowed)
        assertTrue(thermalBlocked.reason!!.contains("too warm"))

        // Low battery (<15%) while unplugged blocks setup; charging allows it
        val batteryBlocked = HardwareProfile.checkAdmission(
            freeStorageBytes = 2 * gib,
            requiredDownloadBytes = 60 * mib,
            batteryPercent = 12,
            isCharging = false,
            thermalSevere = false,
        )
        assertFalse(batteryBlocked.allowed)
        assertTrue(batteryBlocked.reason!!.contains("12%"))

        val batteryChargingAllowed = HardwareProfile.checkAdmission(
            freeStorageBytes = 2 * gib,
            requiredDownloadBytes = 60 * mib,
            batteryPercent = 12,
            isCharging = true,
            thermalSevere = false,
        )
        assertTrue(batteryChargingAllowed.allowed)

        // Insufficient storage (< required + 128 MiB reserve) blocks setup with deficit in MB
        val storageBlocked = HardwareProfile.checkAdmission(
            freeStorageBytes = 100 * mib,
            requiredDownloadBytes = 60 * mib,
            batteryPercent = 90,
            isCharging = false,
            thermalSevere = false,
        )
        assertFalse(storageBlocked.allowed)
        assertTrue(storageBlocked.reason!!.contains("88 MB"))

        // Healthy device passes admission
        val allowed = HardwareProfile.checkAdmission(
            freeStorageBytes = 500 * mib,
            requiredDownloadBytes = 60 * mib,
            batteryPercent = 50,
            isCharging = false,
            thermalSevere = false,
        )
        assertTrue(allowed.allowed)
        assertNull(allowed.reason)
    }
}
