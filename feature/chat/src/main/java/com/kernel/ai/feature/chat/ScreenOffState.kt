package com.kernel.ai.feature.chat

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager

/**
 * True when the screen is off or the keyguard is locked. A locked device with the screen on
 * (lock-screen assistant, #1989) stops the activity exactly like screen-off does, and is not user
 * navigation, so speech must keep running.
 */
internal fun Context.isScreenOffOrLocked(): Boolean {
    val interactive = (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    val locked = (getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isKeyguardLocked == true
    return screenOffOrLockedState(interactive = interactive, keyguardLocked = locked)
}

/** Pure rule behind [isScreenOffOrLocked]: any non-interactive or keyguard-locked state counts. */
internal fun screenOffOrLockedState(interactive: Boolean, keyguardLocked: Boolean): Boolean =
    !interactive || keyguardLocked
