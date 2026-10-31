package com.kernel.ai.bootrestore

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/** Isolated application: Hilt only, with no normal Jandal startup jobs or user data. */
@HiltAndroidApp
class BootRestoreAcceptanceApplication : Application()
