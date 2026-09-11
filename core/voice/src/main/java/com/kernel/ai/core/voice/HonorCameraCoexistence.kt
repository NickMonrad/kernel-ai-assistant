package com.kernel.ai.core.voice

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log

private const val TAG = "HonorCamera"

/**
 * #1502: on the Honor Magic 8 Pro (BKQ-N49), MagicOS refuses to start stock camera video
 * recording while a background app holds a `VOICE_RECOGNITION` capture. Jandal's wake detector
 * holds exactly such a capture, and the camera app receives no platform callback that could warn
 * Jandal before it gives up — audio configuration/audio-focus/camera callbacks were all evaluated
 * on device and provide no usable pre-recording signal.
 *
 * The approved workaround is deliberately narrow: on the proven device only, Jandal watches for
 * [CAMERA_PACKAGE] becoming the foreground app and proactively releases its own microphone
 * capture, then re-arms when the camera leaves. Detecting the camera needs Usage Access, which on
 * this device is therefore a prerequisite for running the wake word at all.
 *
 * Scope rules — everything here applies to the `HONOR` + `BKQ-N49` pair only:
 * - other devices never require Usage Access, never poll for foreground apps, and keep the
 *   existing wake path unchanged;
 * - the workaround is not generalised to other Honor models, other MagicOS releases, or other
 *   camera applications without separate physical evidence.
 */
object HonorCameraCoexistence {

    /** Stock Honor camera package proven to contend for the microphone on BKQ-N49. */
    const val CAMERA_PACKAGE = "com.hihonor.camera"

    internal const val AFFECTED_MANUFACTURER = "honor"
    internal const val AFFECTED_MODEL = "BKQ-N49"

    /**
     * True only for the device class where MagicOS microphone arbitration was proven.
     * Model-exact on purpose: manufacturer-wide matching would claim support that has not been
     * validated on any other Honor device.
     */
    fun isAffectedDevice(manufacturer: String?, model: String?): Boolean =
        manufacturer?.trim()?.equals(AFFECTED_MANUFACTURER, ignoreCase = true) == true &&
            model?.trim()?.equals(AFFECTED_MODEL, ignoreCase = true) == true

    /** True when this device needs the camera-coexistence workaround. */
    fun isAffectedDevice(context: Context): Boolean =
        isAffectedDevice(Build.MANUFACTURER, Build.MODEL)

    /**
     * Whether Usage Access is currently granted.
     *
     * `PACKAGE_USAGE_STATS` is a special (AppOps) access: declaring the permission grants
     * nothing, the user grants it from system settings, and they can revoke it again at any time.
     * Never assume that declaring the permission means access exists.
     */
    fun hasUsageAccess(context: Context): Boolean = try {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
        appOps?.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        ) == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        Log.w(TAG, "Usage Access state could not be determined", e)
        false
    }

    /**
     * Whether Jandal may hold the wake microphone right now.
     *
     * Unaffected devices: always. Affected device: only with Usage Access — without it Jandal
     * cannot learn that the camera needs the microphone and would silently block video recording,
     * so listening must not run at all.
     */
    fun isWakeCaptureAllowed(context: Context): Boolean =
        !isAffectedDevice(context) || hasUsageAccess(context)

    /**
     * True when wake capture must be withheld because the Honor Camera is (or may be) the app in
     * front of the user (#1502).
     *
     * This asks for the *current* foreground package instead of a recent-transition window: after
     * a service or process restart the in-memory suspension latch is gone, and a camera that has
     * been in front for longer than any window produces no transition at all. Usage aggregation
     * still reports it (verified on BKQ-N49 with the camera foreground for over 100 s), and the
     * package that takes over from the camera is reported within the same sub-cadence delay, so
     * this also covers a voice session ending between the camera appearing and the monitor's next
     * poll.
     */
    fun shouldWithholdWakeCapture(context: Context): Boolean {
        if (!isAffectedDevice(context)) return false
        return mustWithholdWakeCapture(UsageStatsCurrentForegroundSource(context), CAMERA_PACKAGE)
    }

    /**
     * Usage Access settings surface, package-scoped where the OEM supports it so the user lands
     * on Jandal's own row rather than the full app list.
     */
    fun usageAccessSettingsIntent(context: Context): Intent {
        val packageScoped = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
        return if (context.packageManager.resolveActivity(packageScoped, 0) != null) {
            packageScoped
        } else {
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        }
    }
}

/**
 * Whether the wake detector may be re-armed after the Honor Camera leaves the foreground.
 *
 * Every condition the workaround depends on is re-checked here, because any of them can change
 * while the camera is in front of the user:
 *
 * - [heyJandalEnabled] — the user may have disabled Hey Jandal while the camera was open.
 * - [recordAudioGranted] — microphone permission can be revoked while Jandal is suspended.
 * - [captureSuspended] — Jandal's own microphone latch; still set means something else (the
 *   camera, or a lost camera-coexistence capability) is holding the microphone.
 * - [voiceSessionActive] — another Jandal voice session (push-to-talk, assistant, widget) owns the
 *   microphone; re-arming would contend with it.
 * - [captureAllowed] — the device-level capability to run wake capture at all, which on the
 *   affected device requires Usage Access (see [HonorCameraCoexistence.isWakeCaptureAllowed]).
 */
fun mayRearmAfterCamera(
    heyJandalEnabled: Boolean,
    recordAudioGranted: Boolean,
    captureSuspended: Boolean,
    voiceSessionActive: Boolean,
    captureAllowed: Boolean,
): Boolean = heyJandalEnabled && recordAudioGranted && !captureSuspended && !voiceSessionActive && captureAllowed

/**
 * Whether wake capture must be withheld because [targetPackage] may own the foreground.
 *
 * A state that cannot be read — no package reported, or the query threw — withholds capture. The
 * two costs are not symmetric: withholding leaves the wake word quiet until the next foreground
 * transition, while taking the microphone blocks the camera, which is the defect #1502 exists to
 * prevent.
 */
internal fun mustWithholdWakeCapture(
    source: ForegroundPackageSource,
    targetPackage: String,
): Boolean = try {
    val current = source.currentForegroundPackage()
    current == null || current == targetPackage
} catch (e: Exception) {
    Log.w(TAG, "current foreground package could not be read — withholding wake capture", e)
    true
}
