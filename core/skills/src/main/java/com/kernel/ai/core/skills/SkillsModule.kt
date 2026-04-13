package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.natives.GetSystemInfoSkill
import com.kernel.ai.core.skills.natives.GetWeatherSkill
import com.kernel.ai.core.skills.natives.SaveMemorySkill
import com.kernel.ai.core.skills.natives.TurnOffFlashlightSkill
import com.kernel.ai.core.skills.natives.TurnOnFlashlightSkill
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class SkillsModule {

    @Binds
    @IntoSet
    abstract fun bindGetSystemInfoSkill(skill: GetSystemInfoSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindSaveMemorySkill(skill: SaveMemorySkill): Skill

    @Binds
    @IntoSet
    abstract fun bindGetWeatherSkill(skill: GetWeatherSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindTurnOnFlashlightSkill(skill: TurnOnFlashlightSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindTurnOffFlashlightSkill(skill: TurnOffFlashlightSkill): Skill
}
