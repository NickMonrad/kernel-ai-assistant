package com.kernel.ai.core.skills

import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.kernel.ai.core.skills.natives.GetSystemInfoSkill
import com.kernel.ai.core.skills.natives.ConvertCurrencySkill
import com.kernel.ai.core.skills.natives.GetWeatherSkill
import com.kernel.ai.core.skills.natives.GetWeatherUnifiedSkill
import com.kernel.ai.core.skills.natives.SaveMemorySkill
import com.kernel.ai.core.skills.natives.SearchMemorySkill
import com.kernel.ai.core.skills.SkillRegistry
import com.kernel.ai.core.skills.intent.CalendarSlotExtractor
import com.kernel.ai.core.skills.intent.IntentContractRegistry
import com.kernel.ai.core.skills.intent.IntentRecoveryOrchestrator
import com.kernel.ai.core.skills.intent.IntentSlotExtractor
import com.kernel.ai.core.skills.slot.SlotFillerManager
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SkillsModule {

    @Binds
    @IntoSet
    abstract fun bindLoadSkillSkill(skill: LoadSkillSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindGetSystemInfoSkill(skill: GetSystemInfoSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindSaveMemorySkill(skill: SaveMemorySkill): Skill

    @Binds
    @IntoSet
    abstract fun bindSearchMemorySkill(skill: SearchMemorySkill): Skill

    @Binds
    @IntoSet
    abstract fun bindGetWeatherSkill(skill: GetWeatherSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindGetWeatherUnifiedSkill(skill: GetWeatherUnifiedSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindRunIntentSkill(skill: RunIntentSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindRunJsSkill(skill: RunJsSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindQueryWikipediaSkill(skill: QueryWikipediaSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindMealPlannerSkill(skill: MealPlannerSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindConvertCurrencySkill(skill: ConvertCurrencySkill): Skill

    /** Bind MiniLMIntentClassifier as the IntentClassifier for QuickIntentRouter. */
    @Binds
    @Singleton
    abstract fun bindIntentClassifier(impl: MiniLMIntentClassifier): QuickIntentRouter.IntentClassifier

    @Binds
    @IntoSet
    abstract fun bindCalendarSlotExtractor(impl: CalendarSlotExtractor): IntentSlotExtractor

    companion object {
        /** Provide a QuickIntentRouter wired with the MiniLM-backed classifier and contract registry. */
        @Provides
        @Singleton
        fun provideQuickIntentRouter(
            classifier: QuickIntentRouter.IntentClassifier,
            registry: IntentContractRegistry,
        ): QuickIntentRouter = QuickIntentRouter(
            classifier = classifier,
            intentContractRegistry = registry,
        )

        /** Provide the nowProvider for NativeIntentHandler (defaults to LocalDate.now()). */
        @Provides
        @Singleton
        fun provideNowProvider(): () -> java.time.LocalDate = { java.time.LocalDate.now() }

        /** Provide the singleton IntentContractRegistry. */
        @Provides
        @Singleton
        fun provideIntentContractRegistry(): IntentContractRegistry = IntentContractRegistry()

        /** Provide the IntentRecoveryOrchestrator with all dependencies. */
        @Provides
        @Singleton
        fun provideIntentRecoveryOrchestrator(
            registry: IntentContractRegistry,
            slotFillerManager: SlotFillerManager,
            skillRegistry: SkillRegistry,
            extractors: Set<@JvmSuppressWildcards IntentSlotExtractor>,
        ): IntentRecoveryOrchestrator = IntentRecoveryOrchestrator(
            registry = registry,
            slotFillerManager = slotFillerManager,
            skillRegistry = skillRegistry,
            extractors = extractors,
        )

        /** Wrap [KernelAIToolSet] into a [ToolProvider] for the LiteRT-LM SDK. */
        @Provides
        @Singleton
        fun provideToolProvider(toolSet: KernelAIToolSet): ToolProvider = tool(toolSet)
    }
}
