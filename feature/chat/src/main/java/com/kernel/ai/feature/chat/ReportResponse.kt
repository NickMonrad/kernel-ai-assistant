package com.kernel.ai.feature.chat

import android.content.Intent
import android.net.Uri
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** Replace with Nick's dedicated Play support address before release. Never use a personal address. */
internal const val SUPPORT_EMAIL = "REPLACE_WITH_DEDICATED_SUPPORT_EMAIL@example.invalid"

internal fun reportResponseMailtoUri(
    response: String,
    appVersion: String,
    model: String,
): String {
    val body = """Reason (please describe why this response is inappropriate):

AI response:
$response

App version: $appVersion
Model: $model
"""
    fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    return "mailto:$SUPPORT_EMAIL?subject=${encode("Report inappropriate AI response")}&body=${encode(body)}"
}

internal fun buildReportResponseIntent(
    response: String,
    appVersion: String,
    model: String,
): Intent = Intent(Intent.ACTION_SENDTO, Uri.parse(reportResponseMailtoUri(response, appVersion, model)))
