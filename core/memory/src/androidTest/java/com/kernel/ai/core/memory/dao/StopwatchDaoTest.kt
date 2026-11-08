package com.kernel.ai.core.memory.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.entity.StopwatchLapEntity
import com.kernel.ai.core.memory.entity.StopwatchStateEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StopwatchDaoTest {
    private lateinit var database: KernelDatabase
    private lateinit var stopwatchDao: StopwatchDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, KernelDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        stopwatchDao = database.stopwatchDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun upsertState_preservesExistingLaps() = runBlocking {
        stopwatchDao.upsertState(
            StopwatchStateEntity(
                id = "primary",
                status = "RUNNING",
                accumulatedElapsedMs = 3_000L,
                runningSinceElapsedRealtimeMs = 10_000L,
                runningSinceWallClockMs = 50_000L,
                updatedAt = 50_000L,
            ),
        )
        stopwatchDao.insertLap(
            StopwatchLapEntity(
                stopwatchId = "primary",
                lapNumber = 1,
                elapsedMs = 7_500L,
                splitMs = 7_500L,
                createdAt = 56_000L,
            ),
        )

        stopwatchDao.upsertState(
            StopwatchStateEntity(
                id = "primary",
                status = "PAUSED",
                accumulatedElapsedMs = 7_500L,
                runningSinceElapsedRealtimeMs = null,
                runningSinceWallClockMs = null,
                updatedAt = 56_000L,
            ),
        )

        val laps = stopwatchDao.getLaps("primary")
        val state = stopwatchDao.getState("primary")

        assertEquals(1, laps.size)
        assertEquals(1, laps.single().lapNumber)
        assertEquals("PAUSED", state?.status)
        assertEquals(7_500L, state?.accumulatedElapsedMs)
    }
}
