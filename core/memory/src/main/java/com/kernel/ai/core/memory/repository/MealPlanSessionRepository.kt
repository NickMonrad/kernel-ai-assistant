package com.kernel.ai.core.memory.repository

import com.kernel.ai.core.memory.dao.MealPlanSessionDao
import com.kernel.ai.core.memory.entity.MealPlanSessionEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Conversation-scoped repository for meal-planner session state.
 *
 * One session per conversation. Used by [ChatViewModel] to inject structured
 * session context into meal-planner prompts and to persist state transitions
 * across the multi-stage flow.
 */
@Singleton
class MealPlanSessionRepository @Inject constructor(
    private val dao: MealPlanSessionDao,
) {

    suspend fun getSession(conversationId: String): MealPlanSessionEntity? =
        dao.getByConversationId(conversationId)

    suspend fun upsert(session: MealPlanSessionEntity) {
        dao.upsert(session.copy(updatedAt = System.currentTimeMillis()))
    }

    /**
     * Creates or resets the session for [conversationId] to initial collecting state.
     * Clears all structured fields while preserving the conversationId primary key.
     */
    suspend fun createOrReset(conversationId: String) {
        val session = MealPlanSessionEntity(
            conversationId = conversationId,
            status = "collecting_preferences",
            peopleCount = null,
            days = null,
            dietaryRestrictionsJson = "[]",
            proteinPreferencesJson = "[]",
            highLevelPlanJson = null,
            currentDayIndex = null,
        )
        dao.upsert(session)
    }

    suspend fun deleteByConversationId(conversationId: String) {
        dao.deleteByConversationId(conversationId)
    }

    suspend fun deleteAll() {
        dao.deleteAll()
    }

    // ── Convenience mutators ──

    suspend fun updateStatus(conversationId: String, status: String) {
        val existing = dao.getByConversationId(conversationId)
            ?: return  // nothing to update
        upsert(existing.copy(status = status, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updatePreferences(
        conversationId: String,
        peopleCount: Int? = null,
        days: Int? = null,
        dietaryRestrictionsJson: String? = null,
        proteinPreferencesJson: String? = null,
        currentDayIndex: Int? = null,
    ) {
        val existing = dao.getByConversationId(conversationId)
            ?: return
        upsert(
            existing.copy(
                peopleCount = peopleCount ?: existing.peopleCount,
                days = days ?: existing.days,
                dietaryRestrictionsJson = dietaryRestrictionsJson ?: existing.dietaryRestrictionsJson,
                proteinPreferencesJson = proteinPreferencesJson ?: existing.proteinPreferencesJson,
                currentDayIndex = currentDayIndex ?: existing.currentDayIndex,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun saveHighLevelPlan(conversationId: String, planJson: String) {
        val existing = dao.getByConversationId(conversationId)
            ?: return
        upsert(
            existing.copy(
                highLevelPlanJson = planJson,
                status = "high_level_plan_ready",
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun advanceDay(conversationId: String) {

        val existing = dao.getByConversationId(conversationId)

            ?: return

        val nextDay = (existing.currentDayIndex ?: 0) + 1

        val totalDays = existing.days

        val isLastDay = totalDays != null && nextDay >= totalDays

        upsert(

            existing.copy(

                currentDayIndex = nextDay,

                // Only mark completed when the user has seen all recipes and says 'done'.

                // The coordinator handles the final transition to WRITING_ARTIFACTS -> COMPLETED.

                status = if (isLastDay) "recipe_review" else "generating_recipes",

                updatedAt = System.currentTimeMillis(),

            ),

        )

    }

    suspend fun markCompleted(conversationId: String) {
        updateStatus(conversationId, "completed")
    }
}
