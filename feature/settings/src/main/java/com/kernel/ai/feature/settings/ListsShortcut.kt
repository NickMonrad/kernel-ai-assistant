package com.kernel.ai.feature.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.kernel.ai.feature.settings.R

/**
 * Canonical launcher shortcut for the Lists feature.
 *
 * Both the static launcher shortcut (declared in `app/src/main/res/xml/shortcuts.xml`) and the
 * in-app "Add Lists shortcut to Home screen" pin action build from this single contract, so they
 * always share the same id, label, icon, and destination route. The destination is expressed via
 * the `navigation_route` extra that [com.kernel.ai.MainActivity] / `KernelNavHost` already consume
 * for the widget and assistant deep-links — no new routing concept.
 */
object ListsShortcut {
    const val ID = "lists"
    const val NAV_ROUTE = "lists"

    /** Destination route extra name shared with MainActivity / KernelNavHost. */
    const val NAV_ROUTE_EXTRA = "navigation_route"

    @StringRes
    val LABEL_RES: Int = R.string.shortcut_lists_label

    @DrawableRes
    val ICON_RES: Int = R.drawable.ic_shortcut_lists

    fun buildIntent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setClassName(context, "com.kernel.ai.MainActivity")
            putExtra(NAV_ROUTE_EXTRA, NAV_ROUTE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

    fun buildShortcutInfo(context: Context): ShortcutInfo =
        ShortcutInfo.Builder(context, ID)
            .setShortLabel(context.getString(LABEL_RES))
            .setIcon(Icon.createWithResource(context, ICON_RES))
            .setIntent(buildIntent(context))
            .build()

    /**
     * Pin the canonical Lists shortcut to the home screen.
     *
     * Returns [PinResult.Requested] only when the device supports pinning AND the pin request was
     * accepted; otherwise [PinResult.Unsupported]. The caller must NOT report success when the
     * result is [PinResult.Unsupported] — pinning is a user-confirmed, asynchronous system flow and
     * we never claim the shortcut is placed.
     */
    fun requestPin(context: Context): PinResult {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return PinResult.Unsupported
        if (!manager.isRequestPinShortcutSupported) {
            return PinResult.Unsupported
        }
        val ok = runCatching { manager.requestPinShortcut(buildShortcutInfo(context), null) }
            .getOrDefault(false)
        return if (ok) PinResult.Requested else PinResult.Unsupported
    }

    enum class PinResult { Requested, Unsupported }
}
