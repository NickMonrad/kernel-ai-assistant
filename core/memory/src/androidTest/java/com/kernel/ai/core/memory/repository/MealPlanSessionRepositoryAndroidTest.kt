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
import com.kernel.ai.core.memory.mealplan.CanonicalGroceryItem
import com.kernel.ai.core.memory.mealplan.GroceryNormalizationStatus
import com.kernel.ai.core.memory.mealplan.MealPlanDraftDay
import com.kernel.ai.core.memory.mealplan.MealPlanSessionStatus
import com.kernel.ai.core.memory.mealplan.RecipeDraft
import com.kernel.ai.core.memory.mealplan.RecipeDraftIngredient
import com.kernel.ai.core.memory.mealplan.RecipeDraftMethodStep
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
    private lateinit var listItemDao: ListItemDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = MealPlanSessionRepository(
            sessionDao = database.mealPlanSessionDao(),
            dayDao = database.mealPlanDayDao(),
            recipeVersionDao = database.mealPlanRecipeVersionDao(),
            groceryItemDao = database.mealPlanGroceryItemDao(),
            projectionWriteDao = database.mealPlanProjectionWriteDao(),
            listItemDao = database.listItemDao(),
            listNameDao = database.listNameDao(),
        )
        listItemDao = database.listItemDao()
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

        val listNames = listItemDao.getAllLists()
        val shoppingListName = listNames.single { it.endsWith("Shopping List") }
        val recipeListName = listNames.single { it.contains("Day 1 — Chicken Stir Fry") }

        assertEquals(
            listOf("500 g chicken thigh", "2 onions"),
            listItemDao.getByList(shoppingListName).map { it.item },
        )
        assertEquals(
            listOf("Slice the vegetables.", "Stir-fry everything until glossy."),
            listItemDao.getByList(recipeListName).map { it.item },
        )
        assertEquals(2, projectionCount(updated.sessionId, "PLAN_SHOPPING_LIST", superseded = null))
        assertEquals(2, projectionCount(updated.sessionId, "RECIPE_LIST", superseded = null))
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
        val originalRecipeList = listItemDao.getAllLists().single { it.contains("Day 1 — Chicken Stir Fry") }

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

        val currentLists = listItemDao.getAllLists()
        val shoppingListName = currentLists.single { it.endsWith("Shopping List") }
        val regeneratedRecipeList = currentLists.single { it.contains("Day 1 — Lemon Chicken") }

        assertEquals(MealPlanSessionStatus.AWAITING_USER_EDIT_OR_RECOVERY, regenerated.status)
        assertEquals(2, regenerated.days.single().currentRecipeVersion)
        assertFalse(currentLists.contains(originalRecipeList))
        assertEquals(listOf("1 lemon"), listItemDao.getByList(shoppingListName).map { it.item })
        assertEquals(listOf("Roast the chicken with lemon."), listItemDao.getByList(regeneratedRecipeList).map { it.item })
        assertEquals(1, projectionCount(regenerated.sessionId, "PLAN_SHOPPING_LIST", superseded = false))
        assertEquals(2, projectionCount(regenerated.sessionId, "PLAN_SHOPPING_LIST", superseded = true))
        assertEquals(1, projectionCount(regenerated.sessionId, "RECIPE_LIST", superseded = false))
        assertEquals(2, projectionCount(regenerated.sessionId, "RECIPE_LIST", superseded = true))
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

    private companion object {
        private const val MIGRATION_DB_NAME = "meal-plan-migration-test"
    }
}
