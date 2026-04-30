package com.kernel.ai.core.memory

import com.kernel.ai.core.memory.dao.MealPlanSessionDao
import com.kernel.ai.core.memory.entity.MealPlanSessionEntity
import com.kernel.ai.core.memory.repository.MealPlanSessionRepository
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MockKExtension::class)
class MealPlanSessionRepositoryTest {

    private val dao: MealPlanSessionDao = mockk()

    private lateinit var repository: MealPlanSessionRepository

    @BeforeEach
    fun setUp() {
        repository = MealPlanSessionRepository(dao)
    }

    // ─────────────────────────────── getSession ──────────────────────────────

    @Test
    fun `getSession returns entity when dao returns one`() = runTest {
        val session = MealPlanSessionEntity(conversationId = "conv-1", status = "active")
        coEvery { dao.getByConversationId("conv-1") } returns session

        val result = repository.getSession("conv-1")

        assertEquals(session, result)
        coVerify(exactly = 1) { dao.getByConversationId("conv-1") }
    }

    @Test
    fun `getSession returns null when dao returns null`() = runTest {
        coEvery { dao.getByConversationId("nonexistent") } returns null

        val result = repository.getSession("nonexistent")

        assertNull(result)
    }

    // ─────────────────────────────── upsert ──────────────────────────────

    @Test
    fun `upsert calls dao with updated timestamp`() = runTest {
        val originalTs = 1000L
        val session = MealPlanSessionEntity(
            conversationId = "conv-1",
            status = "collecting_preferences",
            updatedAt = originalTs,
        )
        var capturedTs: Long? = null
        coEvery { dao.upsert(any()) } answers {
            capturedTs = firstArg<MealPlanSessionEntity>().updatedAt
            Unit
        }

        repository.upsert(session)

        assertNotNull(capturedTs)
        assertEquals(true, capturedTs!! > originalTs, "upsert must refresh updatedAt")
        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    // ─────────────────────────────── createOrReset ──────────────────────────────

    @Test
    fun `createOrReset inserts fresh session with default values`() = runTest {
        coEvery { dao.upsert(any()) } just Runs

        repository.createOrReset("conv-reset")

        coVerify(exactly = 1) { dao.upsert(match {
            it.conversationId == "conv-reset" &&
            it.status == "collecting_preferences" &&
            it.peopleCount == null &&
            it.days == null &&
            it.dietaryRestrictionsJson == "[]" &&
            it.proteinPreferencesJson == "[]" &&
            it.highLevelPlanJson == null &&
            it.currentDayIndex == null
        }) }
    }

    @Test
    fun `createOrReset overwrites existing session`() = runTest {
        coEvery { dao.upsert(any()) } just Runs

        repository.createOrReset("conv-existing")

        coVerify(exactly = 1) { dao.upsert(any()) }
    }

    // ─────────────────────────────── deleteByConversationId ──────────────────────────────

    @Test
    fun `deleteByConversationId delegates to dao`() = runTest {
        coEvery { dao.deleteByConversationId(any()) } just Runs

        repository.deleteByConversationId("conv-del")

        coVerify(exactly = 1) { dao.deleteByConversationId("conv-del") }
    }

    // ─────────────────────────────── deleteAll ──────────────────────────────

    @Test
    fun `deleteAll delegates to dao`() = runTest {
        coEvery { dao.deleteAll() } just Runs

        repository.deleteAll()

        coVerify(exactly = 1) { dao.deleteAll() }
    }

    // ─────────────────────────────── updateStatus ──────────────────────────────

    @Test
    fun `updateStatus updates existing session`() = runTest {
        val existing = MealPlanSessionEntity(
            conversationId = "conv-status",
            status = "collecting_preferences",
        )
        coEvery { dao.getByConversationId("conv-status") } returns existing
        coEvery { dao.upsert(any()) } just Runs

        repository.updateStatus("conv-status", "high_level_plan_ready")

        coVerify(exactly = 1) { dao.upsert(match {
            it.status == "high_level_plan_ready" &&
            it.conversationId == "conv-status"
        }) }
    }

    @Test
    fun `updateStatus does nothing when session does not exist`() = runTest {
        coEvery { dao.getByConversationId("ghost") } returns null
        coEvery { dao.upsert(any()) } just Runs

        repository.updateStatus("ghost", "completed")

        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    // ─────────────────────────────── updatePreferences ──────────────────────────────

    @Test
    fun `updatePreferences merges provided fields with existing`() = runTest {
        val existing = MealPlanSessionEntity(
            conversationId = "conv-pref",
            peopleCount = 2,
            days = 3,
            dietaryRestrictionsJson = """["vegetarian"]""",
        )
        coEvery { dao.getByConversationId("conv-pref") } returns existing
        coEvery { dao.upsert(any()) } just Runs

        repository.updatePreferences(
            conversationId = "conv-pref",
            peopleCount = 4,
            dietaryRestrictionsJson = """["vegetarian","vegan"]""",
        )

        coVerify(exactly = 1) { dao.upsert(match {
            it.peopleCount == 4 &&
            it.days == 3 &&
            it.dietaryRestrictionsJson == """["vegetarian","vegan"]"""
        }) }
    }

    @Test
    fun `updatePreferences preserves existing fields when not provided`() = runTest {
        val existing = MealPlanSessionEntity(
            conversationId = "conv-preserve",
            peopleCount = 6,
            days = 7,
            proteinPreferencesJson = """["beef"]""",
        )
        coEvery { dao.getByConversationId("conv-preserve") } returns existing
        coEvery { dao.upsert(any()) } just Runs

        repository.updatePreferences(conversationId = "conv-preserve", days = 5)

        coVerify(exactly = 1) { dao.upsert(match {
            it.peopleCount == 6 &&
            it.days == 5 &&
            it.proteinPreferencesJson == """["beef"]"""
        }) }
    }

    @Test
    fun `updatePreferences does nothing when session does not exist`() = runTest {
        coEvery { dao.getByConversationId("no-session") } returns null
        coEvery { dao.upsert(any()) } just Runs

        repository.updatePreferences(conversationId = "no-session", peopleCount = 2)

        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    // ─────────────────────────────── saveHighLevelPlan ──────────────────────────────

    @Test
    fun `saveHighLevelPlan updates plan and status`() = runTest {
        val existing = MealPlanSessionEntity(
            conversationId = "conv-plan",
            status = "collecting_preferences",
        )
        coEvery { dao.getByConversationId("conv-plan") } returns existing
        coEvery { dao.upsert(any()) } just Runs

        repository.saveHighLevelPlan("conv-plan", """{"day1":"Pasta"}""")

        coVerify(exactly = 1) { dao.upsert(match {
            it.status == "high_level_plan_ready" &&
            it.highLevelPlanJson == """{"day1":"Pasta"}"""
        }) }
    }

    @Test
    fun `saveHighLevelPlan does nothing when session does not exist`() = runTest {
        coEvery { dao.getByConversationId("no-plan") } returns null
        coEvery { dao.upsert(any()) } just Runs

        repository.saveHighLevelPlan("no-plan", """{}""")

        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    // ─────────────────────────────── advanceDay ──────────────────────────────

    @Test
    fun `advanceDay increments currentDayIndex`() = runTest {
        val existing = MealPlanSessionEntity(
            conversationId = "conv-day",
            days = 5,
            currentDayIndex = 1,
        )
        coEvery { dao.getByConversationId("conv-day") } returns existing
        coEvery { dao.upsert(any()) } just Runs

        repository.advanceDay("conv-day")

        coVerify(exactly = 1) { dao.upsert(match {
            it.currentDayIndex == 2 &&
            it.status == "generating_recipes"
        }) }
    }

    @Test

    fun `advanceDay sets recipe_review when past last day`() = runTest {

        val existing = MealPlanSessionEntity(

            conversationId = "conv-last",

            days = 3,

            currentDayIndex = 2,

        )

        coEvery { dao.getByConversationId("conv-last") } returns existing

        coEvery { dao.upsert(any()) } just Runs



        repository.advanceDay("conv-last")



        coVerify(exactly = 1) { dao.upsert(match {

            it.currentDayIndex == 3 &&

            it.status == "recipe_review"

        }) }

    }

    @Test
    fun `advanceDay starts from 0 when currentDayIndex is null`() = runTest {
        val existing = MealPlanSessionEntity(
            conversationId = "conv-null",
            days = 5,
            currentDayIndex = null,
        )
        coEvery { dao.getByConversationId("conv-null") } returns existing
        coEvery { dao.upsert(any()) } just Runs

        repository.advanceDay("conv-null")

        coVerify(exactly = 1) { dao.upsert(match {
            it.currentDayIndex == 1 &&
            it.status == "generating_recipes"
        }) }
    }

    @Test
    fun `advanceDay does nothing when session does not exist`() = runTest {
        coEvery { dao.getByConversationId("no-day") } returns null
        coEvery { dao.upsert(any()) } just Runs

        repository.advanceDay("no-day")

        coVerify(exactly = 0) { dao.upsert(any()) }
    }

    // ─────────────────────────────── markCompleted ──────────────────────────────

    @Test
    fun `markCompleted sets status to completed`() = runTest {
        val existing = MealPlanSessionEntity(
            conversationId = "conv-done",
            status = "generating_recipes",
        )
        coEvery { dao.getByConversationId("conv-done") } returns existing
        coEvery { dao.upsert(any()) } just Runs

        repository.markCompleted("conv-done")

        coVerify(exactly = 1) { dao.upsert(match {
            it.status == "completed"
        }) }
    }

    @Test
    fun `markCompleted does nothing when session does not exist`() = runTest {
        coEvery { dao.getByConversationId("no-done") } returns null
        coEvery { dao.upsert(any()) } just Runs

        repository.markCompleted("no-done")

        coVerify(exactly = 0) { dao.upsert(any()) }
    }
}
