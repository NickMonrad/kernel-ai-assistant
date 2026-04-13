package com.kernel.ai.core.skills.natives

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import com.kernel.ai.core.skills.Skill
import com.kernel.ai.core.skills.SkillCall
import com.kernel.ai.core.skills.SkillResult
import com.kernel.ai.core.skills.SkillSchema
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "KernelAI"

@Singleton
class TurnOffFlashlightSkill @Inject constructor(
    @ApplicationContext private val context: Context,
) : Skill {

    override val name = "turn_off_flashlight"
    override val description =
        "Turns the device torch/flashlight off. Use when user says 'turn off torch', 'turn off flashlight', or 'torch off'."
    override val schema = SkillSchema()

    override suspend fun execute(call: SkillCall): SkillResult {
        return setTorch(false)
    }

    private fun setTorch(enabled: Boolean): SkillResult {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            if (cameraId != null) {
                cameraManager.setTorchMode(cameraId, enabled)
                SkillResult.Success("Torch turned off.")
            } else {
                SkillResult.Failure(name, "No flash unit found on this device.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TurnOffFlashlightSkill failed", e)
            SkillResult.Failure(name, e.message ?: "Unknown error")
        }
    }
}
