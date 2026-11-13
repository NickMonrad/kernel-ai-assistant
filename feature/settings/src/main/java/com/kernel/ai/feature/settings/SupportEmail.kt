package com.kernel.ai.feature.settings

import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

/** Dedicated Jandal support address, also published in PRIVACY.md. Never use a personal address. */
internal const val SUPPORT_EMAIL_ADDRESS = "jandal.ai.support@gmail.com"

/** Device identity included in a support email body (#1840). */
data class SupportDeviceInfo(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
) {
    companion object {
        fun current(): SupportDeviceInfo = SupportDeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}

internal fun supportEmailSubject(buildInfo: DiagnosticBuildInfo): String =
    "Jandal AI v${buildInfo.versionName} (${buildInfo.versionCode}) feedback"

internal fun supportEmailBody(
    buildInfo: DiagnosticBuildInfo,
    device: SupportDeviceInfo,
    description: String,
    includeUserContent: Boolean,
): String = buildString {
    val phone = listOf(device.manufacturer, device.model).filter { it.isNotBlank() }.joinToString(" ")
    appendLine("Phone: ${phone.ifBlank { "unknown" }}")
    appendLine("Android: ${device.androidRelease.ifBlank { "unknown" }} (API ${device.sdkInt})")
    appendLine("App: v${buildInfo.versionName} (${buildInfo.versionCode}) ${buildInfo.buildType}")
    appendLine("Commit: ${buildInfo.gitSha}")
    appendLine("Built: ${buildInfo.buildTimestamp}")
    appendLine("Chats and messages in attached log: ${if (includeUserContent) "included" else "removed"}")
    appendLine()
    appendLine("What happened:")
    append(description.trim().ifEmpty { "(not described)" })
    appendLine()
}

/**
 * User-mediated email intent: opens the user's email app pre-filled, with the log attached.
 * The user reviews and sends it; Jandal never sends anything itself. `ACTION_SEND` is used
 * (not `ACTION_SENDTO`) because `mailto:` composers do not reliably accept attachments.
 */
internal fun buildSupportEmailIntent(
    subject: String,
    body: String,
    attachment: Uri?,
): Intent = Intent(Intent.ACTION_SEND).apply {
    type = "text/plain"
    putExtra(Intent.EXTRA_EMAIL, arrayOf(SUPPORT_EMAIL_ADDRESS))
    putExtra(Intent.EXTRA_SUBJECT, subject)
    putExtra(Intent.EXTRA_TEXT, body)
    if (attachment != null) {
        putExtra(Intent.EXTRA_STREAM, attachment)
        clipData = ClipData.newRawUri("", attachment)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

internal const val SUPPORT_EMAIL_CHOOSER_TITLE = "Email support"

/** Packages that handle `mailto:`, i.e. the user's email apps (declared in `<queries>`). */
internal fun emailAppPackages(packageManager: PackageManager): List<String> = runCatching {
    packageManager.queryIntentActivities(
        Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")),
        PackageManager.ResolveInfoFlags.of(0),
    ).mapNotNull { it.activityInfo?.packageName }.distinct()
}.getOrDefault(emptyList())

/**
 * Chooser limited to email apps: one `ACTION_SEND` target per [emailPackages] entry that can
 * receive it. The share sheet ignores a `mailto:` selector, so the targets are listed explicitly.
 * With no email app found, it falls back to the normal share sheet.
 */
internal fun buildSupportEmailChooser(
    email: Intent,
    emailPackages: List<String>,
    canHandle: (Intent) -> Boolean,
): Intent {
    val targets = emailPackages.map { Intent(email).setPackage(it) }.filter(canHandle)
    if (targets.isEmpty()) return Intent.createChooser(email, SUPPORT_EMAIL_CHOOSER_TITLE)
    return Intent.createChooser(targets.first(), SUPPORT_EMAIL_CHOOSER_TITLE).apply {
        if (targets.size > 1) putExtra(Intent.EXTRA_INITIAL_INTENTS, targets.drop(1).toTypedArray())
    }
}
