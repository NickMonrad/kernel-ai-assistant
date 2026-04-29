package com.kernel.ai.core.skills

import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import com.kernel.ai.core.skills.natives.GetSystemInfoSkill
import com.kernel.ai.core.skills.natives.GetWeatherSkill
import com.kernel.ai.core.skills.natives.GetWeatherUnifiedSkill
import com.kernel.ai.core.skills.natives.SaveMemorySkill
import com.kernel.ai.core.skills.natives.SearchMemorySkill
import com.kernel.ai.core.skills.MealPlannerCollectSkill
import com.kernel.ai.core.skills.MealPlannerCompleteSkill
import com.kernel.ai.core.skills.MealPlannerPlanSkill
import com.kernel.ai.core.skills.MealPlannerRecipeSkill
import com.kernel.ai.core.skills.MealPlannerSkill
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
    abstract fun bindMealPlannerCollectSkill(skill: MealPlannerCollectSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindMealPlannerPlanSkill(skill: MealPlannerPlanSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindMealPlannerRecipeSkill(skill: MealPlannerRecipeSkill): Skill

    @Binds
    @IntoSet
    abstract fun bindMealPlannerCompleteSkill(skill: MealPlannerCompleteSkill): Skill


    @Binds
    @IntoSet
    abstract fun bindSaveMealPlanStateSkill(skill: SaveMealPlanStateSkill): Skill

    /** Bind MiniLMIntentClassifier as the IntentClassifier for QuickIntentRouter. */
    @Binds
    @Singleton
    abstract fun bindIntentClassifier(impl: MiniLMIntentClassifier): QuickIntentRouter.IntentClassifier

    companion object {
        /** Provide a QuickIntentRouter wired with the MiniLM-backed classifier. */
        @Provides
        @Singleton
        fun provideQuickIntentRouter(
            classifier: QuickIntentRouter.IntentClassifier,
        ): QuickIntentRouter = QuickIntentRouter(classifier = classifier)


        /** Provides the deterministic meal-planner coordinator. */

        @Provides

        @Singleton

        fun provideMealPlannerCoordinator(

            sessionRepo: com.kernel.ai.core.memory.repository.MealPlanSessionRepository,

            skillRegistry: dagger.Lazy<SkillRegistry>,

        ): MealPlannerCoordinator = MealPlannerCoordinator(sessionRepo, skillRegistry)





        /** Wrap [KernelAIToolSet] into a [ToolProvider] for the LiteRT-LM SDK. */
        @Provides
        @Singleton
        fun provideToolProvider(toolSet: KernelAIToolSet): ToolProvider = tool(toolSet)
    }
}
