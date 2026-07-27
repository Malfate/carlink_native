package com.carlink.platform

import android.content.Context
import android.media.AudioManager
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import android.view.WindowManager
import com.carlink.BuildConfig
import com.carlink.util.WindowMetricsCompat

/**
 * PlatformDetector - Detects hardware platform characteristics for configuration selection.
 *
 * PURPOSE:
 * Identifies Intel x86/x86_64 platforms and GM AAOS devices at runtime to enable
 * platform-specific optimizations. No root access required.
 *
 * DETECTION METHODS:
 * - Build.SUPPORTED_ABIS: Primary CPU architecture detection (x86, x86_64, arm64-v8a, etc.)
 * - Build.MANUFACTURER/PRODUCT/DEVICE: GM AAOS device identification
 * - MediaCodecList: Intel OMX codec detection
 * - AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE: Native audio sample rate
 *
 * USAGE:
 * Call detect(context) during video/audio subsystem initialization. PlatformInfo is
 * NOT cached at this layer — every call re-runs all detection steps. CarlinkManager
 * stores the returned PlatformInfo locally and forwards it to AudioConfig.forPlatform;
 * the cluster platform-switch TODO in CarlinkClusterService also plans to consume
 * PlatformInfo.isGmAaos from here. Re-detection on display-mode reinit is redundant
 * but cheap (Build properties + one MediaCodecList scan).
 *
 * Log output uses android.util.Log directly rather than the project Logger — this
 * class runs before the Logger is fully wired and any failure here should not
 * propagate into the file-log pipeline.
 *
 * Reference: https://developer.android.com/ndk/guides/abis
 */
object PlatformDetector {
    private const val TAG = "CARLINK_PLATFORM"

    /**
     * Platform information data class.
     *
     * @property isIntel True if CPU architecture is x86 or x86_64
     * @property isGmAaos True if device is GM AAOS. Primary signal is
     *   Build.MANUFACTURER="gm"; also matches the legacy Harman_Samsung string, "gminfo" in
     *   product/device (Info 3.7), and the VCU/VCUNH1 literals (burmese / "VCU" build id).
     *   2024 Silverado gminfo37 reports manufacturer="gm" (confirmed 6 detections across 3
     *   POTATO sessions 2026-04-20: product=full_gminfo37_gb, device=gminfo37). See
     *   [detectGmAaos] for why the manufacturer check matters — name-based matching alone
     *   excluded every GM EV.
     * @property cpuArch Primary CPU ABI (e.g., "arm64-v8a", "x86_64")
     * @property hasIntelCodec True if an Intel video codec is available. Naive
     *   substring match on ".contains("Intel")" in the decoder name — fragile if the
     *   vendor ever renames.
     * @property hardwareH264DecoderName The detected hardware H.264 decoder name (any vendor)
     * @property nativeSampleRate Device's native audio output sample rate in Hz
     * @property manufacturer Device manufacturer string
     * @property product Device product string
     * @property device Device name string
     * @property buildId Build fingerprint id (Build.ID). On the GM VCU/VCUNH1 platform this
     *   carries the "VCU" family prefix (CT5 firmware: "VCUUM-371.5"); used by [isVcuCluster].
     * @property isBroxton True if Build.BOARD/HARDWARE/PRODUCT contains "broxton".
     *   EMPIRICALLY FALSE on gminfo37 (POTATO 2026-04-20 "Broxton platform: false" despite
     *   gminfo37 being Apollo Lake — GM does not publish the SoC codename at Build level).
     *   Effectively unreachable on all known devices; delete unless a Build property ever
     *   surfaces "broxton".
     * @property displayWidth Native display width in pixels (0 if unknown). CURRENTLY
     *   UNUSED beyond debug logging — MainActivity re-reads WindowMetrics independently
     *   to compute adapter viewArea/safeArea; this copy is redundant.
     * @property displayHeight Native display height in pixels (0 if unknown). See [displayWidth].
     */
    data class PlatformInfo(
        val isIntel: Boolean,
        val isGmAaos: Boolean,
        val cpuArch: String,
        val hasIntelCodec: Boolean,
        val hardwareH264DecoderName: String? = null,
        val nativeSampleRate: Int,
        val manufacturer: String,
        val product: String,
        val device: String,
        val buildId: String = "",
        val sdkInt: Int = Build.VERSION.SDK_INT,
        val isBroxton: Boolean = false,
        val displayWidth: Int = 0,
        val displayHeight: Int = 0,
    ) {
        /**
         * Returns true if device is the GM gminfo37 (2400x960 display).
         *
         * Load-bearing in two places: it selects the immersive UI defaults via
         * [requiresImmersiveDefaults], and it is the exclusion term that keeps Info 3.7 out of
         * the VCU family fallback in [isVcuCluster]. Do not delete.
         */
        fun isGmInfo37(): Boolean = device.equals("gminfo37", ignoreCase = true)

        /**
         * Returns true if running on Google's AAOS reference emulator
         * (product=sdk_gcar_arm64 / sdk_gcar_x86 / sdk_gcar_*). Used as a stand-in for
         * gminfo37 during local development — same 3P-app constraints, and the only way
         * to validate gminfo37-specific defaults without the Silverado hardware.
         */
        fun isAaosEmulator(): Boolean = product.startsWith("sdk_gcar", ignoreCase = true)

        /**
         * Composite: device should receive "gminfo37-like" UI defaults
         * (fullscreen-immersive, OEM icon hidden, etc.). True on gminfo37 itself and on the
         * VCU/VCUNH1 radios ([isVcuCluster]).
         *
         * VCU was added because the previous `isGmInfo37()`-only test left every GM EV
         * defaulting to SYSTEM_UI_VISIBLE: system bars eating vertical space, projection
         * rendered in a window rather than edge-to-edge, and CarPlay's OEM "Exit" tile drawn
         * on top of GM's own back-nav. That is both the less native-feeling default and the
         * more fragile one — revision [144] fixed an inset double-count that can only occur in
         * SYSTEM_UI_VISIBLE, and recorded that immersive is "structurally immune (no
         * windowInsetsPadding)".
         *
         * Still false elsewhere — including the AAOS emulator (a DEBUG APK on the emulator no
         * longer defaults to immersive) — preserving existing factory defaults for non-GM 3P
         * targets. This only sets the DEFAULT; the user's Display Mode choice, once persisted,
         * always wins (see DisplayModePreference).
         */
        fun requiresImmersiveDefaults(): Boolean = isGmInfo37() || isVcuCluster()

        /**
         * Returns true on the GM VCU / VCUNH1 (Bosch, AAOS 14) platform — e.g. the 2026 CT5.
         *
         * On VCUNH1 the instrument cluster is driven by a separate QNX safety-domain VM that
         * renders a native GM Altia sprite chosen from the `Maneuver.TYPE_*` enum; the app-side
         * maneuver bitmap / CarIcon URI is MASKED and never reaches the cluster (see
         * `documents/reference/gminfo/projection/cluster_maneuver_mapping.md` §0/§4). So on this
         * platform the per-maneuver bitmap compose ([com.carlink.navigation.compose.ComposedIconStore] /
         * IconBitmapRenderer) and the AA-bitmap cluster-icon shim are pure wasted work — callers
         * use this to skip them while keeping the enum/angle lever (the only thing that matters here).
         *
         * Detection is two-tier.
         *
         * TIER 1 — exact signatures (BEST-EFFORT — no VCUNH1 hardware on hand) derived from the
         * CT5 `Radio-IVE-86384258-AAOS14-UQBM` build.prop:
         *   ro.product.{system,vendor}.device = "burmese"
         *   ro.product.{system,vendor}.name   = "burmese_orange"
         *   ro.build.id / ro.vendor.build.id  = "VCUUM-371.5"   (the "VCU" family prefix)
         *   ro.product.board / ro.board.platform = "msmnile"     (shared SoC — NOT keyed on)
         *
         * TIER 2 — [isGmAaosPost13NonInfo37], the family fallback. Tier 1 came from a single
         * vehicle, so it recognizes one firmware line rather than the platform; a Sierra EV /
         * Silverado EV can legitimately miss all three literals. See that function for why
         * under-detection is the expensive direction.
         *
         * gminfo37 (Silverado) reports device="gminfo37" / product="full_gminfo37_gb" and Build.ID
         * without the VCU prefix, and is excluded explicitly from tier 2 — gminfo3.7 behavior is
         * unaffected by either tier.
         */
        fun isVcuCluster(): Boolean =
            device.equals("burmese", ignoreCase = true) ||
                product.contains("burmese", ignoreCase = true) ||
                buildId.startsWith("VCU", ignoreCase = true) ||
                isGmAaosPost13NonInfo37()

        /**
         * Family fallback for the VCU signature above: a GM AAOS head unit on API 34+
         * (Android 14) that is NOT gminfo3.7.
         *
         * The three literal signatures in [isVcuCluster] were all derived from ONE vehicle's
         * build.prop (a 2026 CT5), so they identify that firmware line rather than the platform.
         * A Sierra EV / Silverado EV reports different device and product strings and may well
         * not carry the "VCU" Build.ID prefix, which would leave it misdetected as an
         * icon-capable cluster.
         *
         * Getting this wrong is not free. With the gate false, ComposedIconStore keeps composing
         * per-maneuver bitmaps that GM's VMSPlugin discards, and revision [117] measured those
         * compose bursts blocking video reads for ~2s across 28 maneuvers — a zero-frame gap,
         * then STAGE[drop] and visible H.264 corruption. So the failure mode of under-detecting
         * is a stuttering, pixelating projection stream on every route load.
         *
         * The bound is safe in both directions: gminfo3.7 is excluded explicitly, and GM shipped
         * AAOS 12 there, so no Info 3.7 vehicle reaches API 34 by this route. Any other GM AAOS 14
         * radio is VCU-family per the platform notes in README.md, and the only behavior this
         * enables is *skipping* work whose output that cluster ignores.
         */
        private fun isGmAaosPost13NonInfo37(): Boolean =
            isGmAaos && sdkInt >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !isGmInfo37()

        /**
         * Returns true if Intel-specific MediaCodec fixes should be applied.
         * Requires BOTH Intel architecture AND Intel codec presence.
         * ARM-based GM AAOS devices will return false.
         */
        fun requiresIntelMediaCodecFixes(): Boolean = isIntel && hasIntelCodec

        /**
         * Returns true if GM AAOS audio optimizations should be applied.
         *
         * Keyed on [isGmAaos] ALONE — deliberately NOT `isIntel && isGmAaos`.
         *
         * The condition these settings compensate for is GM's AudioFlinger denying
         * AUDIO_OUTPUT_FLAG_FAST to third-party apps (`createTrack_l(8): ... denied by server`
         * while system apps pass). That is an app-privilege policy, not a CPU-architecture
         * quirk, so it applies equally to the ARM GM head units — the VCU/VCUNH1 (Bosch,
         * `ro.board.platform=msmnile`) radios in the EVs and newer ICE vehicles.
         *
         * The previous `isIntel &&` conjunct silently routed every ARM GM vehicle into
         * AudioConfig's generic `else` fallback, which requests PERFORMANCE_MODE_LOW_LATENCY
         * (pointless once FAST is denied, and per the AudioConfig header a potential source of
         * added jitter) and sizes the rings at 1000/400ms instead of the profiled 750/300ms.
         * That fallback is annotated STILL UNVERIFIED in AudioConfig — it was never meant to be
         * the path a known GM device takes.
         *
         * Intel-specific MediaCodec handling is unaffected; that stays on
         * [requiresIntelMediaCodecFixes], which is a genuine architecture concern.
         */
        fun requiresGmAaosAudioFixes(): Boolean = isGmAaos

        override fun toString(): String =
            "PlatformInfo(arch=$cpuArch, intel=$isIntel, gm=$isGmAaos, " +
                "hwDecoder=${hardwareH264DecoderName ?: "software"}, " +
                "nativeRate=${nativeSampleRate}Hz, mfr=$manufacturer, product=$product, device=$device, " +
                "buildId=$buildId, sdk=$sdkInt, vcu=${isVcuCluster()}, " +
                "gmAudioFixes=${requiresGmAaosAudioFixes()}, immersive=${requiresImmersiveDefaults()})"
    }

    /**
     * Detect platform characteristics.
     *
     * @param context Android context for accessing system services
     * @return PlatformInfo with all detected characteristics
     */
    fun detect(context: Context): PlatformInfo {
        val cpuArch = detectCpuArchitecture()
        val isIntel = cpuArch == "x86_64" || cpuArch == "x86"

        val manufacturer = Build.MANUFACTURER ?: ""
        val product = Build.PRODUCT ?: ""
        val device = Build.DEVICE ?: ""
        val board = Build.BOARD ?: ""
        val hardware = Build.HARDWARE ?: ""
        val buildId = Build.ID ?: ""

        val isGmAaos = detectGmAaos(manufacturer, product, device, buildId)
        val (_, hardwareH264DecoderName) = detectHardwareH264Decoder()
        val hasIntelCodec = hardwareH264DecoderName?.contains("Intel", ignoreCase = true) == true
        val nativeSampleRate = detectNativeSampleRate(context)

        // Detect Intel Broxton/Apollo Lake platform (used in gminfo37)
        // Broxton uses Intel Atom x7 (Apollo Lake) with HD Graphics 505
        val isBroxton =
            board.contains("broxton", ignoreCase = true) ||
                hardware.contains("broxton", ignoreCase = true) ||
                product.contains("broxton", ignoreCase = true)

        // Detect display resolution
        val (displayWidth, displayHeight) = detectDisplayResolution(context)

        val info =
            PlatformInfo(
                isIntel = isIntel,
                isGmAaos = isGmAaos,
                cpuArch = cpuArch,
                hasIntelCodec = hasIntelCodec,
                hardwareH264DecoderName = hardwareH264DecoderName,
                nativeSampleRate = nativeSampleRate,
                manufacturer = manufacturer,
                product = product,
                device = device,
                buildId = buildId,
                sdkInt = Build.VERSION.SDK_INT,
                isBroxton = isBroxton,
                displayWidth = displayWidth,
                displayHeight = displayHeight,
            )

        // Logged unconditionally (not DEBUG-gated like the detail lines below): this single
        // line is the only way to confirm on a RELEASE build which platform branch a given
        // head unit actually took — gmAudioFixes / immersive / vcu are all decided from it.
        // Diagnosing a misdetected vehicle without this means guessing.
        Log.i(TAG, "[PLATFORM] Detected: $info")

        if (BuildConfig.DEBUG) {
            Log.i(TAG, "[PLATFORM] Hardware H.264 decoder: ${hardwareH264DecoderName ?: "none (software fallback)"}")
            Log.i(TAG, "[PLATFORM] Intel-specific fixes: ${info.requiresIntelMediaCodecFixes()}")
            Log.i(TAG, "[PLATFORM] GM AAOS audio fixes: ${info.requiresGmAaosAudioFixes()}")
            Log.i(TAG, "[PLATFORM] Broxton platform: $isBroxton, Display: ${displayWidth}x$displayHeight")
        }

        return info
    }

    /**
     * Detect display resolution from WindowManager.
     *
     * @param context Android context
     * @return Pair of (width, height) in pixels
     */
    private fun detectDisplayResolution(context: Context): Pair<Int, Int> =
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null) {
                // WindowMetricsCompat: currentWindowMetrics on API 30+, getRealMetrics on API 29.
                val bounds = WindowMetricsCompat.displayBounds(windowManager)
                Pair(bounds.width(), bounds.height())
            } else {
                Pair(0, 0)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect display resolution: ${e.message}")
            Pair(0, 0)
        }

    /**
     * Detect primary CPU architecture from Build.SUPPORTED_ABIS.
     *
     * Reference: https://developer.android.com/ndk/guides/abis
     */
    private fun detectCpuArchitecture(): String =
        try {
            Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect CPU architecture: ${e.message}")
            "unknown"
        }

    /**
     * Detect if device is GM AAOS based on manufacturer, product, device and build id.
     *
     * Observed on 2024 Silverado gminfo37 (root README.md):
     * - Manufacturer: "gm"
     * - Product: "full_gminfo37_gb"           (matches via contains("gminfo"))
     * - Device: "gminfo37"                    (matches via startsWith("gminfo"))
     *
     * MANUFACTURER IS THE LOAD-BEARING CHECK, not the "gminfo" name matches.
     *
     * This function previously keyed only on Harman_Samsung (documented dead code on real
     * GM hardware) plus the literal string "gminfo" in product/device. That silently
     * excluded the entire GM VCU/VCUNH1 family, which names itself after the vehicle line
     * rather than the radio generation — the CT5 build.prop reports device="burmese",
     * product="burmese_orange". A Sierra EV / Silverado EV matches none of the "gminfo"
     * patterns, so isGmAaos came back FALSE on a GM head unit.
     *
     * That miss cascaded: [PlatformInfo.requiresGmAaosAudioFixes] and the tier-2 fallback in
     * [PlatformInfo.isVcuCluster] are both gated on isGmAaos, so on an EV they silently
     * evaluated to false and every GM-specific tuning path was skipped — the app fell back to
     * the generic "unknown vendor" profile on hardware we can identify perfectly well.
     *
     * `Build.MANUFACTURER == "gm"` was documented in this file the whole time and simply
     * wasn't being used. It is the stable, model-independent signal; the burmese/VCU literals
     * below are belt-and-braces in case a variant reports something else there.
     */
    private fun detectGmAaos(
        manufacturer: String,
        product: String,
        device: String,
        buildId: String,
    ): Boolean =
        manufacturer.equals("gm", ignoreCase = true) ||
            manufacturer.equals("Harman_Samsung", ignoreCase = true) ||
            product.contains("gminfo", ignoreCase = true) ||
            device.startsWith("gminfo", ignoreCase = true) ||
            // GM VCU / VCUNH1 family literals (CT5 build.prop; see PlatformInfo.isVcuCluster).
            device.equals("burmese", ignoreCase = true) ||
            product.contains("burmese", ignoreCase = true) ||
            buildId.startsWith("VCU", ignoreCase = true)

    /**
     * Detect the best available hardware H.264 decoder.
     *
     * Uses MediaCodecList.REGULAR_CODECS — deliberately excludes ALL_CODECS, since
     * vendor-specific non-regular decoders are rarely suitable for real-time video
     * playback without additional tuning. If no hardware decoder is surfaced as
     * REGULAR the method returns (false, null) and the app falls back to software.
     *
     * @return Pair of (isHardwareDecoder, codecName)
     */
    private fun detectHardwareH264Decoder(): Pair<Boolean, String?> =
        try {
            val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            val hwDecoder =
                codecList.codecInfos.firstOrNull { info ->
                    !info.isEncoder &&
                        info.isHardwareAccelerated &&
                        info.supportedTypes.any { type ->
                            type.equals("video/avc", ignoreCase = true)
                        }
                }
            if (hwDecoder != null) {
                if (BuildConfig.DEBUG) Log.i(TAG, "[PLATFORM] Found hardware H.264 decoder: ${hwDecoder.name}")
                Pair(true, hwDecoder.name)
            } else {
                if (BuildConfig.DEBUG) Log.i(TAG, "[PLATFORM] No hardware H.264 decoder found")
                Pair(false, null)
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect hardware codec: ${e.message}")
            Pair(false, null)
        }

    /**
     * Detect native audio output sample rate.
     *
     * Uses AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE to get the optimal output rate.
     * Falls back to 48000 Hz (common automotive rate) if detection fails.
     *
     * Reference: https://developer.android.com/reference/android/media/AudioManager#PROPERTY_OUTPUT_SAMPLE_RATE
     */
    private fun detectNativeSampleRate(context: Context): Int =
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager
                ?.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?: DEFAULT_SAMPLE_RATE
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) Log.w(TAG, "Failed to detect native sample rate: ${e.message}")
            DEFAULT_SAMPLE_RATE
        }

    private const val DEFAULT_SAMPLE_RATE = 48000
}
