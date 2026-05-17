package com.kernel.ai.core.memory.repository

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.dao.ListItemDao
import com.kernel.ai.core.memory.dao.ListNameDao
import com.kernel.ai.core.memory.dao.MealPlanProjectionWriteDao
import com.kernel.ai.core.memory.entity.ListNameEntity
import com.kernel.ai.core.memory.entity.MealPlanProjectionWriteEntity
import com.kernel.ai.core.memory.mealplan.CanonicalGroceryItem
import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.memory.mealplan.MealPlanDayStatus
import com.kernel.ai.core.memory.mealplan.MealPlanDraftDay
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.mealplan.RecipeDraftIngredient
import com.kernel.ai.core.memory.mealplan.RecipeDraftMethodStep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MealPlanSessionRepositoryAndroidTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KernelDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private lateinit var database: KernelDatabase
    private lateinit var repository: MealPlanSessionRepository
    private lateinit var projectionWriteDao: MealPlanProjectionWriteDao
    private lateinit var listItemDao: ListItemDao
    private lateinit var listNameDao: ListNameDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(object : androidx.room.RoomDatabase.Callback() {
                override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    super.onOpen(db)
                    db.execSQL("PRAGMA foreign_keys = ON")
                }
            })
            .build()
        repository = MealPlanSessionRepository(
            database = database,
            sessionDao = database.mealPlanSessionDao(),
            dayDao = database.mealPlanDayDao(),
            recipeVersionDao = database.mealPlanRecipeVersionDao(),
            groceryItemDao = database.mealPlanGroceryItemDao(),
            projectionWriteDao = database.mealPlanProjectionWriteDao(),
            listItemDao = database.listItemDao(),
            listNameDao = database.listNameDao(),
        )
        projectionWriteDao = database.mealPlanProjectionWriteDao()
        listItemDao = database.listItemDao()
        listNameDao = database.listNameDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun persistRecipeDraft_createsRecipeAndShoppingProjections() = runBlocking {
        val session = repository.startOrResume("conv-1")
        val planned = repository.savePlanDraft(
            session.sessionId,
            listOf(
                MealPlanDraftDay(
                    dayIndex = 0,
                    title = "Chicken Stir Fry",
                    summary = "Quick bowl",
                    proteinTags = listOf("chicken"),
                ),
                MealPlanDraftDay(
                    dayIndex = 1,
                    title = "Tofu Curry",
                    summary = "Weeknight curry",
                    proteinTags = listOf("tofu"),
                ),
            ),
        )

        val updated = repository.persistRecipeDraft(
            sessionId = planned.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(
                title = "Chicken Stir Fry",
                method = listOf("Slice the vegetables.", "Stir-fry everything until glossy."),
            ),
            rawModelJson = "{}",
            groceries = listOf(
                grocery(displayText = "500 g chicken thigh", quantity = "500", unit = "g", ingredientName = "chicken thigh"),
                grocery(displayText = "2 onions", ingredientName = "onions", normalizationStatus = GroceryNormalizationStatus.OPAQUE),
            ),
        )

        assertEquals(MealPlanSessionStatus.RECIPES_IN_PROGRESS, updated.status)
        assertEquals(1, updated.pendingGenerationDayIndex)
        assertEquals(1, updated.activeDayIndex)
        assertEquals(1, updated.days.first().currentRecipeVersion)
        assertNotNull(updated.days.first().currentRecipe)

        val allListEntities = listNameDao.getAll()
        val listNames = allListEntities.map { it.name }
        val shoppingListName = listNames.single { it.endsWith("Shopping List") }
        val recipeListName = listNames.single { it.contains("Day 1 — Chicken Stir Fry") }
        assertTrue(shoppingListName.matches(Regex("""Meal Plan \d{4}-\d{2}-\d{2} \(MP-001\) Shopping List""")))
        assertTrue(recipeListName.matches(Regex("""Meal Plan \d{4}-\d{2}-\d{2} \(MP-001\) Day 1 — Chicken Stir Fry""")))
        val shoppingListId = allListEntities.first { it.name == shoppingListName }.id
        val recipeListId = allListEntities.first { it.name == recipeListName }.id

        assertEquals(
            listOf("500 g chicken thigh", "2 onions"),
            listItemDao.getByList(shoppingListId).map { it.text },
        )
        assertEquals(
            listOf("Ingredients", "placeholder ingredient", "Method", "1. Slice the vegetables.", "2. Stir-fry everything until glossy."),
            listItemDao.getByList(recipeListId).map { it.text },
        )
        assertEquals(2, projectionCount(updated.sessionId, "PLAN_SHOPPING_LIST", superseded = null))
        assertEquals(5, projectionCount(updated.sessionId, "RECIPE_LIST", superseded = null))
    }

    @Test
    fun persistRecipeDraft_keeps_failed_days_in_recovery_until_user_retries() = runBlocking {
        val session = repository.startOrResume("conv-failed-day")
        repository.savePlanDraft(
            session.sessionId,
            listOf(
                MealPlanDraftDay(dayIndex = 0, title = "Chicken Stir Fry", summary = "Quick bowl", proteinTags = listOf("chicken")),
                MealPlanDraftDay(dayIndex = 1, title = "Beef Tacos", summary = "Taco night", proteinTags = listOf("beef")),
            ),
        )
        repository.markGenerationFailure(
            sessionId = session.sessionId,
            dayIndex = 0,
            errorCode = "RECIPE_NO_OUTPUT",
            errorMessage = "The model did not return a recipe.",
        )

        val updated = repository.persistRecipeDraft(
            sessionId = session.sessionId,
            dayIndex = 1,
            recipeDraft = recipeDraft(title = "Beef Tacos", method = listOf("Warm the tortillas.", "Cook the beef.")),
            rawModelJson = "{}",
            groceries = listOf(grocery(displayText = "500 g beef mince", quantity = "500", unit = "g", ingredientName = "beef mince")),
        )

        assertEquals(MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY, updated.status)
        assertEquals(null, updated.pendingGenerationKind)
        assertEquals(null, updated.pendingGenerationDayIndex)
        assertEquals(null, updated.activeDayIndex)
        assertEquals(MealPlanDayStatus.FAILED, updated.days.first { it.dayIndex == 0 }.status)
        assertEquals(MealPlanDayStatus.PERSISTED, updated.days.first { it.dayIndex == 1 }.status)
    }

    @Test
    fun persistRecipeDraft_regenerationSupersedesOldProjections() = runBlocking {
        val session = repository.startOrResume("conv-2")
        repository.savePlanDraft(
            session.sessionId,
            listOf(
                MealPlanDraftDay(
                    dayIndex = 0,
                    title = "Chicken Stir Fry",
                    summary = "Quick bowl",
                    proteinTags = listOf("chicken"),
                ),
            ),
        )

        repository.persistRecipeDraft(
            sessionId = session.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(
                title = "Chicken Stir Fry",
                method = listOf("Prep vegetables.", "Stir-fry chicken."),
            ),
            rawModelJson = "{}",
            groceries = listOf(
                grocery(displayText = "500 g chicken thigh", quantity = "500", unit = "g", ingredientName = "chicken thigh"),
                grocery(displayText = "2 onions", ingredientName = "onions", normalizationStatus = GroceryNormalizationStatus.OPAQUE),
            ),
        )
        val originalRecipeListName = listNameDao.getAll().single { it.name.contains("Day 1 — Chicken Stir Fry") }.name
        assertTrue(originalRecipeListName.matches(Regex("""Meal Plan \d{4}-\d{2}-\d{2} \(MP-001\) Day 1 — Chicken Stir Fry""")))

        val regenerated = repository.persistRecipeDraft(
            sessionId = session.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(
                title = "Lemon Chicken",
                method = listOf("Roast the chicken with lemon."),
            ),
            rawModelJson = "{}",
            groceries = listOf(
                grocery(displayText = "1 lemon", ingredientName = "lemon", normalizationStatus = GroceryNormalizationStatus.OPAQUE),
            ),
        )

        val currentListEntities = listNameDao.getAll()
        val currentListNames = currentListEntities.map { it.name }
        val shoppingListName = currentListEntities.single { it.name.endsWith("Shopping List") }.name
        assertTrue(shoppingListName.matches(Regex("""Meal Plan \d{4}-\d{2}-\d{2} \(MP-001\) Shopping List""")))
        val shoppingListId = currentListEntities.single { it.name == shoppingListName }.id
        val regeneratedRecipeListId = currentListEntities.single { it.name.contains("Day 1 — Lemon Chicken") }.id

        assertEquals(MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY, regenerated.status)
        assertEquals(2, regenerated.days.single().currentRecipeVersion)
        assertFalse(currentListNames.contains(originalRecipeListName))
        assertEquals(listOf("1 lemon"), listItemDao.getByList(shoppingListId).map { it.text })
        assertEquals(listOf("Ingredients", "placeholder ingredient", "Method", "1. Roast the chicken with lemon."), listItemDao.getByList(regeneratedRecipeListId).map { it.text })
        assertEquals(1, projectionCount(regenerated.sessionId, "PLAN_SHOPPING_LIST", superseded = false))
        assertEquals(2, projectionCount(regenerated.sessionId, "PLAN_SHOPPING_LIST", superseded = true))
        assertEquals(4, projectionCount(regenerated.sessionId, "RECIPE_LIST", superseded = false))
        assertEquals(5, projectionCount(regenerated.sessionId, "RECIPE_LIST", superseded = true))
    }

    @Test
    fun savePlanDraft_clears_stale_recipe_and_shopping_lists_before_new_plan_review() = runBlocking {
        val session = repository.startOrResume("conv-replan")
        repository.savePlanDraft(
            session.sessionId,
            listOf(MealPlanDraftDay(dayIndex = 0, title = "Chicken Stir Fry", summary = "Quick bowl", proteinTags = listOf("chicken"))),
        )
        repository.persistRecipeDraft(
            sessionId = session.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(title = "Chicken Stir Fry", method = listOf("Prep vegetables.", "Stir-fry chicken.")),
            rawModelJson = "{}",
            groceries = listOf(grocery(displayText = "500 g chicken thigh", quantity = "500", unit = "g", ingredientName = "chicken thigh")),
        )

        val replanned = repository.savePlanDraft(
            session.sessionId,
            listOf(MealPlanDraftDay(dayIndex = 0, title = "Lemon Chicken", summary = "Tray bake", proteinTags = listOf("chicken"))),
        )

        assertEquals(MealPlanSessionStatus.PLAN_REVIEW, replanned.status)
        assertEquals(listOf("Lemon Chicken"), replanned.days.map { it.title })
        assertTrue(listNameDao.getAll().isEmpty())
        assertEquals(0, projectionCount(session.sessionId, "PLAN_SHOPPING_LIST", superseded = false))
        assertEquals(0, projectionCount(session.sessionId, "RECIPE_LIST", superseded = false))
    }

    @Test
    fun cancelSession_deletesPlannerOwnedListsAndSupersedesProjections() = runBlocking {
        val session = repository.startOrResume("conv-3")
        repository.savePlanDraft(
            session.sessionId,
            listOf(
                MealPlanDraftDay(
                    dayIndex = 0,
                    title = "Chicken Stir Fry",
                    summary = "Quick bowl",
                    proteinTags = listOf("chicken"),
                ),
            ),
        )

        repository.persistRecipeDraft(
            sessionId = session.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(
                title = "Chicken Stir Fry",
                method = listOf("Prep vegetables.", "Stir-fry chicken."),
            ),
            rawModelJson = "{}",
            groceries = listOf(
                grocery(displayText = "500 g chicken thigh", quantity = "500", unit = "g", ingredientName = "chicken thigh"),
                grocery(displayText = "2 onions", ingredientName = "onions", normalizationStatus = GroceryNormalizationStatus.OPAQUE),
            ),
        )

        val keepListName = "My Groceries"
        listNameDao.insert(ListNameEntity(name = keepListName, createdAt = 1L, updatedAt = 1L))

        val cancelled = repository.cancelSession(session.sessionId)

        assertEquals(MealPlanSessionStatus.CANCELLED, cancelled.status)
        assertEquals(listOf(keepListName), listNameDao.getAll().map { it.name })
        assertEquals(2, projectionCount(session.sessionId, "PLAN_SHOPPING_LIST", superseded = true))
        assertEquals(5, projectionCount(session.sessionId, "RECIPE_LIST", superseded = true))
        assertEquals(0, projectionCount(session.sessionId, "PLAN_SHOPPING_LIST", superseded = false))
        assertEquals(0, projectionCount(session.sessionId, "RECIPE_LIST", superseded = false))
    }

    @Test
    fun markGenerationFailure_does_not_revive_cancelled_session() = runBlocking {
        val session = repository.startOrResume("conv-cancelled-failure")
        repository.savePlanDraft(
            session.sessionId,
            listOf(
                MealPlanDraftDay(dayIndex = 0, title = "Chicken Stir Fry", summary = "Quick bowl", proteinTags = listOf("chicken")),
            ),
        )
        repository.cancelSession(session.sessionId)

        val updated = repository.markGenerationFailure(
            sessionId = session.sessionId,
            dayIndex = 0,
            errorCode = "RECIPE_NO_OUTPUT",
            errorMessage = "The model did not return a recipe.",
        )

        assertEquals(MealPlanSessionStatus.CANCELLED, updated.status)
        assertEquals(MealPlanDayStatus.DRAFTED, updated.days.single().status)
        assertEquals(null, updated.pendingGenerationKind)
        assertEquals(null, updated.pendingGenerationDayIndex)
    }

    @Test
    fun persistRecipeDraft_deletesLegacyShoppingProjectionNamesDuringRebuild() = runBlocking {
        val session = repository.startOrResume("conv-legacy")
        val planned = repository.savePlanDraft(
            session.sessionId,
            listOf(
                MealPlanDraftDay(
                    dayIndex = 0,
                    title = "Chicken Stir Fry",
                    summary = "Quick bowl",
                    proteinTags = listOf("chicken"),
                ),
            ),
        )
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.ENGLISH).format(Date(planned.createdAt))
        val legacyListName = "Meal Plan $date (${planned.sessionId.take(8)}) Shopping List"
        listNameDao.insert(ListNameEntity(name = legacyListName, createdAt = 1L, updatedAt = 1L))
        projectionWriteDao.insertAll(
            listOf(
                MealPlanProjectionWriteEntity(
                    id = "legacy-shopping-projection",
                    mealPlanSessionId = planned.sessionId,
                    targetKind = "PLAN_SHOPPING_LIST",
                    targetName = legacyListName,
                    sourceKey = "day:${planned.days.single().id}:grocery:legacy",
                    sourceVersion = 1,
                    projectedAt = 1L,
                    supersededAt = null,
                ),
            ),
        )

        repository.persistRecipeDraft(
            sessionId = planned.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(
                title = "Chicken Stir Fry",
                method = listOf("Slice the vegetables.", "Stir-fry everything until glossy."),
            ),
            rawModelJson = "{}",
            groceries = listOf(
                grocery(displayText = "500 g chicken thigh", quantity = "500", unit = "g", ingredientName = "chicken thigh"),
            ),
        )

        val listNames = listNameDao.getAll().map { it.name }
        assertFalse(listNames.contains(legacyListName))
        assertTrue(listNames.any { it.matches(Regex("""Meal Plan \d{4}-\d{2}-\d{2} \(MP-001\) Shopping List""")) })
    }

    @Test
    fun getRecentMealHistory_reads_recent_terminal_days_from_canonical_session_rows() = runBlocking {
        val completed = repository.startOrResume("conv-history-completed")
        repository.savePlanDraft(
            completed.sessionId,
            listOf(
                MealPlanDraftDay(dayIndex = 0, title = "Chicken Stir Fry", summary = "Quick bowl", proteinTags = listOf("chicken")),
                MealPlanDraftDay(dayIndex = 1, title = "Beef Tacos", summary = "Taco night", proteinTags = listOf("beef")),
            ),
        )
        repository.completeSession(completed.sessionId)

        val active = repository.startOrResume("conv-history-active")
        repository.savePlanDraft(
            active.sessionId,
            listOf(
                MealPlanDraftDay(dayIndex = 0, title = "Should Not Appear", summary = "Still active", proteinTags = listOf("chicken")),
            ),
        )

        val cancelled = repository.startOrResume("conv-history-cancelled")
        repository.savePlanDraft(
            cancelled.sessionId,
            listOf(
                MealPlanDraftDay(dayIndex = 0, title = "Tofu Curry", summary = "Weeknight curry", proteinTags = listOf("tofu")),
            ),
        )
        repository.cancelSession(cancelled.sessionId)

        val history = repository.getRecentMealHistory(limit = 3)

        assertEquals(listOf("Tofu Curry", "Chicken Stir Fry", "Beef Tacos"), history.map { it.title })
        assertEquals(listOf(listOf("tofu"), listOf("chicken"), listOf("beef")), history.map { it.proteinTags })
        assertFalse(history.any { it.title == "Should Not Appear" })
    }


    @Test
    fun startOrResume_prunes_only_old_terminal_sessions_that_meet_retention_policy() = runBlocking {
        val prunable = repository.startOrResume("conv-old-completed")
        repository.savePlanDraft(
            prunable.sessionId,
            listOf(MealPlanDraftDay(dayIndex = 0, title = "Chicken Stir Fry", summary = "Quick bowl", proteinTags = listOf("chicken"))),
        )
        repository.persistRecipeDraft(
            sessionId = prunable.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(title = "Chicken Stir Fry", method = listOf("Slice the vegetables.", "Stir-fry everything until glossy.")),
            rawModelJson = "{}",
            groceries = listOf(grocery(displayText = "500 g chicken thigh", quantity = "500", unit = "g", ingredientName = "chicken thigh")),
        )
        repository.completeSession(prunable.sessionId)
        repository.markFinalSummaryWritten(prunable.sessionId)
        val prunableTargetNames = projectionWriteDao.getAllTargetNamesForSession(prunable.sessionId)

        val keepCompleted = repository.startOrResume("conv-old-completed-no-summary")
        repository.savePlanDraft(
            keepCompleted.sessionId,
            listOf(MealPlanDraftDay(dayIndex = 0, title = "Tofu Curry", summary = "Weeknight curry", proteinTags = listOf("tofu"))),
        )
        repository.persistRecipeDraft(
            sessionId = keepCompleted.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(title = "Tofu Curry", method = listOf("Toast spices.", "Simmer the curry.")),
            rawModelJson = "{}",
            groceries = listOf(grocery(displayText = "1 block tofu", ingredientName = "tofu", normalizationStatus = GroceryNormalizationStatus.OPAQUE)),
        )
        repository.completeSession(keepCompleted.sessionId)
        ageCompletedSession(keepCompleted.sessionId, daysAgo = 31)

        val fallbackPrunable = repository.startOrResume("conv-old-completed-fallback")
        repository.savePlanDraft(
            fallbackPrunable.sessionId,
            listOf(MealPlanDraftDay(dayIndex = 0, title = "Lentil Pasta", summary = "Pantry meal", proteinTags = listOf("lentils"))),
        )
        repository.persistRecipeDraft(
            sessionId = fallbackPrunable.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(title = "Lentil Pasta", method = listOf("Boil pasta.", "Stir through lentils.")),
            rawModelJson = "{}",
            groceries = listOf(grocery(displayText = "1 cup lentils", ingredientName = "lentils", normalizationStatus = GroceryNormalizationStatus.OPAQUE)),
        )
        repository.completeSession(fallbackPrunable.sessionId)

        val active = repository.startOrResume("conv-active-old")
        ageCompletedSession(prunable.sessionId, daysAgo = 31)
        ageCompletedSession(fallbackPrunable.sessionId, daysAgo = 91)
        ageActiveSession(active.sessionId, daysAgo = 60)

        repository.startOrResume("conv-fresh")

        assertFalse(sessionExists(prunable.sessionId))
        assertTrue(sessionExists(keepCompleted.sessionId))
        assertFalse(sessionExists(fallbackPrunable.sessionId))
        assertTrue(sessionExists(active.sessionId))
        assertEquals(0, projectionRowsForSession(prunable.sessionId))
        assertEquals(0, mealPlanDayRowsForSession(prunable.sessionId))
        assertEquals(0, recipeVersionRowsForSession(prunable.sessionId))
        assertEquals(0, groceryRowsForSession(prunable.sessionId))
        assertEquals(0, listItemsForNames(prunableTargetNames))
        assertTrue(prunableTargetNames.none { targetName -> listNameDao.getAll().any { it.name == targetName } })
    }

    @Test
    fun startOrResume_prunes_old_cancelled_sessions_and_orphaned_projection_lists() = runBlocking {
        val cancelled = repository.startOrResume("conv-old-cancelled")
        repository.savePlanDraft(
            cancelled.sessionId,
            listOf(MealPlanDraftDay(dayIndex = 0, title = "Chicken Stir Fry", summary = "Quick bowl", proteinTags = listOf("chicken"))),
        )
        repository.persistRecipeDraft(
            sessionId = cancelled.sessionId,
            dayIndex = 0,
            recipeDraft = recipeDraft(title = "Chicken Stir Fry", method = listOf("Slice the vegetables.", "Stir-fry everything until glossy.")),
            rawModelJson = "{}",
            groceries = listOf(grocery(displayText = "500 g chicken thigh", quantity = "500", unit = "g", ingredientName = "chicken thigh")),
        )
        repository.cancelSession(cancelled.sessionId)
        val orphanListName = "Meal Plan orphan (${cancelled.sessionId.take(8)})"
        listNameDao.insert(ListNameEntity(name = orphanListName, createdAt = 1L, updatedAt = 1L))
        projectionWriteDao.insertAll(
            listOf(
                MealPlanProjectionWriteEntity(
                    id = "orphan-${cancelled.sessionId}",
                    mealPlanSessionId = cancelled.sessionId,
                    targetKind = "RECIPE_LIST",
                    targetName = orphanListName,
                    sourceKey = "orphan-source",
                    sourceVersion = 1,
                    projectedAt = 1L,
                    supersededAt = 1L,
                ),
            ),
        )
        ageCancelledSession(cancelled.sessionId, daysAgo = 8)

        repository.startOrResume("conv-fresh-cancelled")

        assertFalse(sessionExists(cancelled.sessionId))
        assertFalse(listNameDao.getAll().any { it.name == orphanListName })
        assertEquals(0, projectionRowsForSession(cancelled.sessionId))
    }
    @Test
    fun migration33To34_preservesExistingListsAndCreatesMealPlannerTables() {
        migrationHelper.createDatabase(MIGRATION_DB_NAME, 33).apply {
            execSQL("INSERT INTO `lists` (`id`, `name`, `createdAt`) VALUES (1, 'Existing list', 1234)")
            close()
        }

        val migratedDb = migrationHelper.runMigrationsAndValidate(
            MIGRATION_DB_NAME,
            34,
            true,
            KernelDatabase.MIGRATION_33_34,
        )

        assertTrue(tableExists(migratedDb, "meal_plan_sessions"))
        assertTrue(tableExists(migratedDb, "meal_plan_days"))
        assertTrue(tableExists(migratedDb, "meal_plan_recipe_versions"))
        assertTrue(tableExists(migratedDb, "meal_plan_grocery_items"))
        assertTrue(tableExists(migratedDb, "meal_plan_projection_writes"))
        migratedDb.query("SELECT name FROM `lists` WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Existing list", cursor.getString(0))
        }
        migratedDb.close()
    }

    private fun projectionCount(sessionId: String, targetKind: String, superseded: Boolean?): Int {
        val supersededClause = when (superseded) {
            true -> " AND supersededAt IS NOT NULL"
            false -> " AND supersededAt IS NULL"
            null -> ""
        }
        val query = SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM meal_plan_projection_writes WHERE mealPlanSessionId = ? AND targetKind = ?$supersededClause",
            arrayOf(sessionId, targetKind),
        )
        return database.openHelper.writableDatabase.query(query).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun projectionRowsForSession(sessionId: String): Int {
        val query = SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM meal_plan_projection_writes WHERE mealPlanSessionId = ?",
            arrayOf(sessionId),
        )
        return database.openHelper.writableDatabase.query(query).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun mealPlanDayRowsForSession(sessionId: String): Int {
        val query = SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM meal_plan_days WHERE mealPlanSessionId = ?",
            arrayOf(sessionId),
        )
        return database.openHelper.writableDatabase.query(query).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun recipeVersionRowsForSession(sessionId: String): Int {
        val query = SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM meal_plan_recipe_versions WHERE mealPlanSessionId = ?",
            arrayOf(sessionId),
        )
        return database.openHelper.writableDatabase.query(query).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun groceryRowsForSession(sessionId: String): Int {
        val query = SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM meal_plan_grocery_items WHERE mealPlanSessionId = ?",
            arrayOf(sessionId),
        )
        return database.openHelper.writableDatabase.query(query).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun listItemsForNames(names: List<String>): Int {
        if (names.isEmpty()) return 0
        val placeholders = names.joinToString(",") { "?" }
        val query = SimpleSQLiteQuery(
            "SELECT COUNT(*) FROM list_items li JOIN lists l ON l.id = li.listId WHERE l.name IN ($placeholders)",
            names.toTypedArray(),
        )
        return database.openHelper.writableDatabase.query(query).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0)
        }
    }

    private fun sessionExists(sessionId: String): Boolean {
        val query = SimpleSQLiteQuery(
            "SELECT EXISTS(SELECT 1 FROM meal_plan_sessions WHERE id = ?)",
            arrayOf(sessionId),
        )
        return database.openHelper.writableDatabase.query(query).use { cursor ->
            cursor.moveToFirst()
            cursor.getInt(0) == 1
        }
    }

    private fun ageCompletedSession(sessionId: String, daysAgo: Int) {
        val cutoff = System.currentTimeMillis() - daysAgo * 24L * 60L * 60L * 1000L
        database.openHelper.writableDatabase.execSQL(
            "UPDATE meal_plan_sessions SET updatedAt = ?, completedAt = ? WHERE id = ?",
            arrayOf<Any>(cutoff, cutoff, sessionId),
        )
    }

    private fun ageCancelledSession(sessionId: String, daysAgo: Int) {
        val cutoff = System.currentTimeMillis() - daysAgo * 24L * 60L * 60L * 1000L
        database.openHelper.writableDatabase.execSQL(
            "UPDATE meal_plan_sessions SET updatedAt = ?, cancelledAt = ? WHERE id = ?",
            arrayOf<Any>(cutoff, cutoff, sessionId),
        )
    }

    private fun ageActiveSession(sessionId: String, daysAgo: Int) {
        val cutoff = System.currentTimeMillis() - daysAgo * 24L * 60L * 60L * 1000L
        database.openHelper.writableDatabase.execSQL(
            "UPDATE meal_plan_sessions SET updatedAt = ? WHERE id = ?",
            arrayOf<Any>(cutoff, sessionId),
        )
    }

    private fun tableExists(db: androidx.sqlite.db.SupportSQLiteDatabase, tableName: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$tableName'").use { cursor ->
            cursor.moveToFirst()
        }

    private fun recipeDraft(title: String, method: List<String>): RecipeDraft = RecipeDraft(
        title = title,
        servings = 4,
        ingredients = listOf(
            RecipeDraftIngredient(
                originalText = "placeholder ingredient",
                amount = "1",
                unit = "tbsp",
                item = "oil",
                note = null,
            ),
        ),
        methodSteps = method.mapIndexed { index, text ->
            RecipeDraftMethodStep(stepNumber = index + 1, text = text)
        },
    )

    private fun grocery(
        displayText: String,
        quantity: String? = null,
        unit: String? = null,
        ingredientName: String? = null,
        normalizationStatus: GroceryNormalizationStatus = GroceryNormalizationStatus.NORMALIZED,
    ): CanonicalGroceryItem = CanonicalGroceryItem(
        displayText = displayText,
        originalText = displayText,
        quantity = quantity,
        unit = unit,
        ingredientName = ingredientName,
        note = null,
        normalizationStatus = normalizationStatus,
        mergeKey = ingredientName,
    )

    @Test
    fun migration34To35_addsNewColumnsAndPreservesListItems() {
        migrationHelper.createDatabase(MIGRATION_DB_NAME, 34).apply {
            execSQL("INSERT INTO `lists` (`id`, `name`, `createdAt`) VALUES (1, 'My List', 1000)")
            execSQL("INSERT INTO `list_items` (`id`, `listName`, `item`, `addedAt`) VALUES (1, 'My List', 'apple', 2000)")
            close()
        }

        val migratedDb = migrationHelper.runMigrationsAndValidate(
            MIGRATION_DB_NAME,
            35,
            true,
            KernelDatabase.MIGRATION_34_35,
        )

        // lists table gains updatedAt and pinned columns
        migratedDb.query("SELECT name, updatedAt, pinned FROM `lists` WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("My List", cursor.getString(0))
            assertEquals(1000L, cursor.getLong(1)) // updatedAt defaults to createdAt
            assertEquals(0, cursor.getInt(2))      // pinned defaults to 0 (false)
        }
        // list_items now has listId FK and text column
        migratedDb.query("SELECT listId, text FROM `list_items` WHERE id = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0)) // listId resolved from name join
            assertEquals("apple", cursor.getString(1)) // text (was item)
        }
        migratedDb.close()
    }

    @Test
    fun migration41To42_backfillsStableMealPlanDisplayCodes() {
        migrationHelper.createDatabase(MIGRATION_DB_NAME, 41).apply {
            execSQL(
                """
                INSERT INTO `meal_plan_sessions` (
                    `id`, `conversationId`, `status`, `peopleCount`, `daysCount`,
                    `dietaryRestrictionsJson`, `proteinPreferencesJson`, `optionalSlotsJson`,
                    `activeDayIndex`, `pendingGenerationKind`, `pendingGenerationDayIndex`,
                    `pendingGenerationStartedAt`, `planVersion`, `finalSummaryWritten`,
                    `createdAt`, `updatedAt`, `completedAt`, `cancelledAt`
                ) VALUES
                    ('session-a', 'conv-a', 'PLAN_REVIEW', 2, 3, '[]', '[]', '{}', NULL, NULL, NULL, NULL, 1, 0, 1000, 1000, NULL, NULL),
                    ('session-b', 'conv-b', 'PLAN_REVIEW', 4, 5, '[]', '[]', '{}', NULL, NULL, NULL, NULL, 1, 0, 1000, 1000, NULL, NULL),
                    ('session-c', 'conv-c', 'PLAN_REVIEW', 4, 5, '[]', '[]', '{}', NULL, NULL, NULL, NULL, 1, 0, 2000, 2000, NULL, NULL)
                """.trimIndent(),
            )
            close()
        }

        val migratedDb = migrationHelper.runMigrationsAndValidate(
            MIGRATION_DB_NAME,
            42,
            true,
            KernelDatabase.MIGRATION_41_42,
        )

        migratedDb.query("SELECT id, displayCode FROM `meal_plan_sessions` ORDER BY createdAt ASC, id ASC").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("session-a", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
            assertTrue(cursor.moveToNext())
            assertEquals("session-b", cursor.getString(0))
            assertEquals(2, cursor.getInt(1))
            assertTrue(cursor.moveToNext())
            assertEquals("session-c", cursor.getString(0))
            assertEquals(3, cursor.getInt(1))
        }
        migratedDb.close()
    }

    private companion object {
        private const val MIGRATION_DB_NAME = "meal-plan-migration-test"
    }
}
