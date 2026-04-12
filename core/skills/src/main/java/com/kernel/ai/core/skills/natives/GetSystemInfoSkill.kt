package com.kernel.ai.core.skills.natives

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.kernel.ai.core.inference.hardware.HardwareProfileDetector
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

/**
 * Skill that returns current device and model runtime information.
 * Replaces the hardcoded [Runtime] block in the system prompt.
 */
@Singleton
class GetSystemInfoSkill @Inject constructor(
    @ApplicationContext private val context: Context,
    private val hardwareProfileDetector: HardwareProfileDetector,
) : Skill {

    override val name = "get_system_info"
    override val description =
        "Returns current device and model runtime information including hardware tier, " +
            "available memory, battery level, and active model."
    override val schema = SkillSchema()

    override suspend fun execute(call: SkillCall): SkillResult {
        return withContext(Dispatchers.Default) {
            try {
                val profile = hardwareProfileDetector.profile

                val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val memInfo = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
                val availMb = memInfo.availMem / (1024 * 1024)

                val batteryIntent = context.registerReceiver(
                    null,
                    IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                val batteryPct = batteryIntent?.let {
                    val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                } ?: -1

                val info = buildString {
                    appendLine("Hardware tier: ${profile.tier.name}")
                    appendLine("SoC: ${profile.socManufacturer} ${profile.socModel}".trim())
                    appendLine("Total RAM: ${profile.ramLabel}")
                    appendLine("RAM available: ${availMb}MB")
                    appendLine("Recommended backend: ${profile.recommendedBackend.name}")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    if (batteryPct >= 0) appendLine("Battery: $batteryPct%")
                }

                Log.d(TAG, "GetSystemInfoSkill executed successfully")
                SkillResult.Success(info.trim())
            } catch (e: Exception) {
                Log.e(TAG, "GetSystemInfoSkill failed", e)
                SkillResult.Failure(name, e.message ?: "Failed to retrieve system info")
            }
        }
    }
}
