package com.kernel.ai.core.skills

import com.kernel.ai.core.skills.natives.GetSystemInfoSkill
import com.kernel.ai.core.skills.natives.SaveMemorySkill
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
}
