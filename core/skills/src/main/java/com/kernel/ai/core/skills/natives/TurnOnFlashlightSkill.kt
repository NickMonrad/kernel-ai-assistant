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
class TurnOnFlashlightSkill @Inject constructor(
    @ApplicationContext private val context: Context,
) : Skill {

    override val name = "turn_on_flashlight"
    override val description =
        "Turns the device torch/flashlight on. Use when user says 'turn on torch', 'turn on flashlight', or 'torch on'."
    override val schema = SkillSchema()

    override suspend fun execute(call: SkillCall): SkillResult {
        return setTorch(true)
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
                SkillResult.Success("Torch turned on.")
            } else {
                SkillResult.Failure(name, "No flash unit found on this device.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "TurnOnFlashlightSkill failed", e)
            SkillResult.Failure(name, e.message ?: "Unknown error")
        }
    }
}
