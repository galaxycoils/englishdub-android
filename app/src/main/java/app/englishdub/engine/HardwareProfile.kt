package app.englishdub.engine

import android.app.ActivityManager
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.os.StatFs
import app.englishdub.data.DubJob
import java.util.Locale

enum class HardwareTier(val label: String) {
    BUDGET("Budget / Low RAM"),
    BALANCED("Balanced / Standard"),
    HIGH_PERFORMANCE("High Performance"),
}

data class CodecDescriptor(
    val name: String,
    val isEncoder: Boolean,
    val supportedTypes: List<String>,
    val isHardwareAccelerated: Boolean,
)

data class DeviceHardwareProfile(
    val totalRamBytes: Long,
    val availableCores: Int,
    val freeStorageBytes: Long,
    val totalStorageBytes: Long = 0L,
    val primaryAbi: String = "arm64-v8a",
    val hasHardwareAacEncoder: Boolean = true,
    val hasHardwareVideoDecoder: Boolean = true,
    val isLowRamDevice: Boolean = false,
    val tier: HardwareTier = HardwareProfile.classifyTier(totalRamBytes, availableCores, primaryAbi, isLowRamDevice),
    val recommendedThreads: Int = HardwareProfile.recommendedThreads(tier, availableCores),
    val recommendedSpeechCacheBytes: Long = HardwareProfile.recommendedSpeechCacheBytes(tier, freeStorageBytes),
    val recommendSpeechPack: Boolean = freeStorageBytes >= HardwareProfile.SPEECH_PACK_HEADROOM_BYTES,
    val recommendVadPack: Boolean = freeStorageBytes >= HardwareProfile.MIN_STORAGE_RESERVE_BYTES + ModelStore.VAD_SIZE,
    val recommendVadEnabled: Boolean = false,
) {
    val summaryLine: String
        get() = HardwareProfile.formatSummaryLine(availableCores, primaryAbi, totalRamBytes, hasHardwareAacEncoder)

    val tuningSummaryLine: String
        get() {
            val threadWord = if (recommendedThreads == 1) "thread" else "threads"
            val cacheMb = recommendedSpeechCacheBytes / (1024 * 1024)
            return "${tier.label} · $recommendedThreads recognition $threadWord · $cacheMb MB speech cache"
        }
}

data class SetupPlan(
    val profile: DeviceHardwareProfile,
    val downloadSpeechPack: Boolean,
    val downloadVadPack: Boolean,
    val translationPacksToDownload: List<String>,
    val enableVadAfterSetup: Boolean,
    val requiredDownloadBytes: Long,
    val allInstalled: Boolean,
    val summaryText: String,
    val detailText: String,
)

data class AdmissionCheck(
    val allowed: Boolean,
    val reason: String? = null,
)

object HardwareProfile {
    const val FOUR_GIB = 4L * 1024 * 1024 * 1024
    const val SEVEN_GIB = 7L * 1024 * 1024 * 1024
    const val MIN_STORAGE_RESERVE_BYTES = 128L * 1024 * 1024
    const val SPEECH_PACK_HEADROOM_BYTES = 300L * 1024 * 1024
    const val TRANSLATION_PACK_ESTIMATE_BYTES = 30L * 1024 * 1024
    const val MIN_BATTERY_PERCENT = 15

    private val cachedCodecSupport: Pair<Boolean, Boolean> by lazy {
        val infos = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos }.getOrNull().orEmpty()
        val descriptors = infos.map { info ->
            CodecDescriptor(
                name = info.name,
                isEncoder = info.isEncoder,
                supportedTypes = info.supportedTypes?.toList().orEmpty(),
                isHardwareAccelerated = isHardwareCodec(info),
            )
        }
        hasFastAacSupport(descriptors) to hasHardwareVideoSupport(descriptors)
    }

    fun isArm64Abi(abi: String): Boolean {
        val clean = abi.trim().lowercase(Locale.US)
        return clean.contains("arm64") || clean.contains("aarch64")
    }

    fun formatAbiLabel(abi: String): String {
        val clean = abi.trim().lowercase(Locale.US)
        return when {
            clean.contains("arm64") || clean.contains("aarch64") -> "ARM64"
            clean.contains("armeabi") || clean == "arm" -> "ARM32"
            clean == "x86_64" || clean == "amd64" -> "x86_64"
            clean == "x86" || clean == "i686" -> "x86"
            clean.isBlank() -> "ARM64"
            else -> abi.trim()
        }
    }

    fun formatRamLabel(totalRamBytes: Long): String {
        if (totalRamBytes <= 0L) return "1 GB RAM"
        val gib = totalRamBytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        val roundedGb = (gib + 0.35).toInt().coerceAtLeast(1)
        return "$roundedGb GB RAM"
    }

    fun formatStorageSize(bytes: Long): String {
        val clamped = bytes.coerceAtLeast(0L)
        val gib = clamped.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gib >= 1.0) {
            "%.1f GB".format(Locale.US, gib)
        } else {
            val mib = (clamped + 512 * 1024) / (1024 * 1024)
            "$mib MB"
        }
    }

    fun formatSummaryLine(
        availableCores: Int,
        primaryAbi: String,
        totalRamBytes: Long,
        hasHardwareAacEncoder: Boolean,
    ): String {
        val cores = availableCores.coerceAtLeast(1)
        val abi = formatAbiLabel(primaryAbi)
        val ram = formatRamLabel(totalRamBytes)
        val aac = if (hasHardwareAacEncoder) "Hardware AAC available" else "Standard AAC"
        return "Detected: ${cores}-core $abi · $ram · $aac"
    }

    fun classifyTier(
        totalRamBytes: Long,
        availableCores: Int,
        primaryAbi: String = "arm64-v8a",
        isLowRamDevice: Boolean = false,
    ): HardwareTier {
        if (isLowRamDevice || !isArm64Abi(primaryAbi) || totalRamBytes <= FOUR_GIB || availableCores <= 4) {
            return HardwareTier.BUDGET
        }
        if (totalRamBytes >= SEVEN_GIB && availableCores >= 8) {
            return HardwareTier.HIGH_PERFORMANCE
        }
        return HardwareTier.BALANCED
    }

    fun recommendedThreads(tier: HardwareTier, availableCores: Int): Int {
        val cores = availableCores.coerceAtLeast(1)
        return when (tier) {
            HardwareTier.BUDGET -> 1
            HardwareTier.BALANCED -> 2
            HardwareTier.HIGH_PERFORMANCE -> 2.coerceAtMost(cores)
        }
    }

    fun recommendedSpeechCacheBytes(tier: HardwareTier, freeStorageBytes: Long): Long {
        if (freeStorageBytes < 1L * 1024 * 1024 * 1024) {
            return 64L * 1024 * 1024
        }
        return when (tier) {
            HardwareTier.BUDGET -> 128L * 1024 * 1024
            HardwareTier.BALANCED -> 256L * 1024 * 1024
            HardwareTier.HIGH_PERFORMANCE ->
                if (freeStorageBytes >= 4L * 1024 * 1024 * 1024) 384L * 1024 * 1024 else 256L * 1024 * 1024
        }
    }

    fun hasFastAacSupport(codecs: List<CodecDescriptor>): Boolean {
        val aacEncoders = codecs.filter { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals("audio/mp4a-latm", ignoreCase = true) }
        }
        return aacEncoders.any { it.isHardwareAccelerated }
    }

    fun hasHardwareVideoSupport(codecs: List<CodecDescriptor>): Boolean =
        codecs.any { codec ->
            !codec.isEncoder && codec.isHardwareAccelerated &&
                codec.supportedTypes.any {
                    it.equals("video/avc", ignoreCase = true) || it.equals("video/hevc", ignoreCase = true)
                }
        }

    fun evaluate(
        totalRamBytes: Long,
        availableCores: Int,
        freeStorageBytes: Long,
        totalStorageBytes: Long = 0L,
        primaryAbi: String = "arm64-v8a",
        hasHardwareAacEncoder: Boolean = true,
        hasHardwareVideoDecoder: Boolean = true,
        isLowRamDevice: Boolean = false,
    ): DeviceHardwareProfile {
        val clampedRam = totalRamBytes.coerceAtLeast(0L)
        val clampedCores = availableCores.coerceAtLeast(1)
        val clampedFree = freeStorageBytes.coerceAtLeast(0L)
        val clampedTotal = maxOf(clampedFree, totalStorageBytes.coerceAtLeast(0L))
        val tier = classifyTier(clampedRam, clampedCores, primaryAbi, isLowRamDevice)
        val threads = recommendedThreads(tier, clampedCores)
        val cacheBytes = recommendedSpeechCacheBytes(tier, clampedFree)
        val recommendVad = clampedFree >= MIN_STORAGE_RESERVE_BYTES + ModelStore.VAD_SIZE
        val recommendSpeech = clampedFree >= SPEECH_PACK_HEADROOM_BYTES
        return DeviceHardwareProfile(
            totalRamBytes = clampedRam,
            availableCores = clampedCores,
            freeStorageBytes = clampedFree,
            totalStorageBytes = clampedTotal,
            primaryAbi = primaryAbi,
            hasHardwareAacEncoder = hasHardwareAacEncoder,
            hasHardwareVideoDecoder = hasHardwareVideoDecoder,
            isLowRamDevice = isLowRamDevice,
            tier = tier,
            recommendedThreads = threads,
            recommendedSpeechCacheBytes = cacheBytes,
            recommendSpeechPack = recommendSpeech,
            recommendVadPack = recommendVad,
            recommendVadEnabled = false,
        )
    }

    fun buildSetupPlan(
        profile: DeviceHardwareProfile,
        speechReady: Boolean,
        vadReady: Boolean,
        installedTranslations: Set<String> = emptySet(),
        jobs: List<DubJob> = emptyList(),
    ): SetupPlan {
        val libraryNeedsSpeech = jobs.any { job ->
            job.mode == "speech" || (job.mode != "english_audio" && job.subtitleUri == null && job.subtitleTrackIndex < 0 && job.state == "missing_pack")
        }
        val wantSpeech = libraryNeedsSpeech || profile.recommendSpeechPack
        // VAD is experimental: download it only through the explicit detector action.

        val downloadSpeech = !speechReady && wantSpeech
        val downloadVad = false

        val neededTranslations = jobs
            .map { it.sourceLanguage }
            .filter { it in listOf("hi", "ur", "ta") && it !in installedTranslations }
            .distinct()
            .sortedBy { listOf("hi", "ur", "ta").indexOf(it) }

        val requiredBytes =
            (if (downloadSpeech) ModelStore.SIZE else 0L) +
                (if (downloadVad) ModelStore.VAD_SIZE else 0L) +
                neededTranslations.size * TRANSLATION_PACK_ESTIMATE_BYTES

        val allInstalled = !downloadSpeech && !downloadVad && neededTranslations.isEmpty()
        val threadWord = if (profile.recommendedThreads == 1) "thread" else "threads"
        val cacheMb = profile.recommendedSpeechCacheBytes / (1024 * 1024)

        val packParts = mutableListOf<String>()
        if (downloadSpeech) packParts += "speech pack (57 MB)"
        if (downloadVad) packParts += "speech detector (0.9 MB)"
        neededTranslations.forEach { code ->
            val langName = OfflineTranslation.languages[code] ?: code
            packParts += "$langName translation (~30 MB)"
        }

        val summaryText = if (allInstalled) {
            "Ready for this phone (${profile.tier.label} · ${profile.recommendedThreads} $threadWord · $cacheMb MB cache). All recommended offline packs are verified."
        } else if (!profile.recommendSpeechPack && !libraryNeedsSpeech && !speechReady && downloadVad) {
            "Storage is tight (${formatStorageSize(profile.freeStorageBytes)} free). Installs lightweight detector (0.9 MB) and tunes ${profile.recommendedThreads}-$threadWord dubbing."
        } else {
            val totalMb = ((requiredBytes + 512 * 1024) / (1024 * 1024)).coerceAtLeast(1)
            "Downloads verified ${packParts.joinToString(" + ")} (~$totalMb MB) and tunes ${profile.recommendedThreads}-$threadWord offline dubbing."
        }

        val detailText = buildString {
            append("Free storage: ${formatStorageSize(profile.freeStorageBytes)} · ")
            if (allInstalled) {
                append("Verified packs ready for offline movie watching.")
            } else {
                append("Recommended download: ${packParts.joinToString(", ")}.")
            }
        }

        return SetupPlan(
            profile = profile,
            downloadSpeechPack = downloadSpeech,
            downloadVadPack = downloadVad,
            translationPacksToDownload = neededTranslations,
            enableVadAfterSetup = profile.recommendVadEnabled && (vadReady || downloadVad),
            requiredDownloadBytes = requiredBytes,
            allInstalled = allInstalled,
            summaryText = summaryText,
            detailText = detailText,
        )
    }

    fun checkAdmission(
        freeStorageBytes: Long,
        requiredDownloadBytes: Long,
        batteryPercent: Int = 100,
        isCharging: Boolean = true,
        thermalSevere: Boolean = false,
    ): AdmissionCheck {
        if (thermalSevere) {
            return AdmissionCheck(false, "The phone is too warm. Let it cool before running auto-setup.")
        }
        if (!isCharging && batteryPercent in 1 until MIN_BATTERY_PERCENT) {
            return AdmissionCheck(
                false,
                "Battery is low ($batteryPercent%). Plug in your phone before downloading offline packs.",
            )
        }
        val minimumRequired = requiredDownloadBytes.coerceAtLeast(0L) + MIN_STORAGE_RESERVE_BYTES
        if (freeStorageBytes < minimumRequired) {
            val deficitBytes = minimumRequired - freeStorageBytes.coerceAtLeast(0L)
            val neededMb = ((deficitBytes + 1024 * 1024 - 1) / (1024 * 1024)).coerceAtLeast(1)
            return AdmissionCheck(
                false,
                "Need about $neededMb MB more free storage for offline setup. Free space and retry.",
            )
        }
        return AdmissionCheck(true, null)
    }

    fun detect(context: Context): DeviceHardwareProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val memInfo = ActivityManager.MemoryInfo()
        activityManager?.getMemoryInfo(memInfo)
        val totalRamBytes = memInfo.totalMem.coerceAtLeast(0L)
        val isLowRam = activityManager?.isLowRamDevice == true
        val availableCores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val primaryAbi = Build.SUPPORTED_ABIS?.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }
        val stat = runCatching { StatFs(context.filesDir.absolutePath) }.getOrNull()
        val freeStorageBytes = stat?.availableBytes?.coerceAtLeast(0L) ?: context.filesDir.usableSpace.coerceAtLeast(0L)
        val totalStorageBytes = stat?.totalBytes?.coerceAtLeast(0L) ?: context.filesDir.totalSpace.coerceAtLeast(0L)
        val (hasFastAac, hasHwVideo) = cachedCodecSupport
        return evaluate(
            totalRamBytes = totalRamBytes,
            availableCores = availableCores,
            freeStorageBytes = freeStorageBytes,
            totalStorageBytes = totalStorageBytes,
            primaryAbi = primaryAbi,
            hasHardwareAacEncoder = hasFastAac,
            hasHardwareVideoDecoder = hasHwVideo,
            isLowRamDevice = isLowRam,
        )
    }

    fun applyOptimalSettings(
        prefs: SharedPreferences,
        profile: DeviceHardwareProfile,
        selectedVoiceName: String? = null,
    ) {
        val editor = prefs.edit()
            .putInt("inferenceThreads", profile.recommendedThreads)
            .putLong("speechCacheMaxBytes", profile.recommendedSpeechCacheBytes)
            .putBoolean("autoSetupCompleted", true)
            .putString("autoSetupTier", profile.tier.name)
            .putString("autoSetupSummary", profile.summaryLine)
        if (!selectedVoiceName.isNullOrBlank() && prefs.getString("voiceName", null).isNullOrBlank()) {
            editor.putString("voiceName", selectedVoiceName)
        }
        editor.apply()
    }

    fun resolveInferenceThreads(context: Context, prefs: SharedPreferences): Int {
        val saved = prefs.getInt("inferenceThreads", 0)
        if (saved > 0) return saved.coerceIn(1, 2)
        return detect(context).recommendedThreads
    }

    fun resolveSpeechCacheBytes(context: Context, prefs: SharedPreferences): Long {
        val saved = prefs.getLong("speechCacheMaxBytes", 0L)
        if (saved >= 64L * 1024 * 1024) return saved
        return CueAudioCache.DEFAULT_MAX_BYTES
    }

    private fun isHardwareCodec(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 29) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase(Locale.US)
        return !name.startsWith("omx.google.") &&
            !name.startsWith("c2.android.") &&
            !name.contains(".sw.")
    }
}
