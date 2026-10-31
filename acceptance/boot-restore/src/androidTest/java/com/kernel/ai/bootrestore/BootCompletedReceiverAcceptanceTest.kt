package com.kernel.ai.bootrestore

import android.app.AlarmManager
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kernel.ai.core.memory.KernelDatabase
import com.kernel.ai.core.memory.entity.ImportantDateEntity
import com.kernel.ai.core.memory.entity.ListItemEntity
import com.kernel.ai.core.memory.entity.ListNameEntity
import dagger.hilt.android.EntryPointAccessors
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BootCompletedReceiverAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val database: KernelDatabase by lazy {
        EntryPointAccessors.fromApplication(
            targetContext.applicationContext,
            BootRestoreDatabaseEntryPoint::class.java,
        ).kernelDatabase()
    }

    @Test
    fun packageAndStartupAreIsolated() {
        assertEquals(TARGET_APPLICATION_ID, targetContext.packageName)
        assertEquals(
            "$TARGET_APPLICATION_ID.test",
            instrumentation.context.packageName,
        )
        assertTrue(targetContext.applicationContext is BootRestoreAcceptanceApplication)

        val packageInfo = targetContext.packageManager.getPackageInfo(
            targetContext.packageName,
            PackageManager.GET_PROVIDERS or PackageManager.GET_PERMISSIONS,
        )
        val authorities = packageInfo.providers.orEmpty()
            .flatMap { it.authority?.split(';').orEmpty() }
            .toSet()
        assertTrue(authorities.isEmpty())

        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()
        assertTrue("android.permission.RECEIVE_BOOT_COMPLETED" in permissions)
        assertFalse("android.permission.USE_EXACT_ALARM" in permissions)
        assertFalse("android.permission.SCHEDULE_EXACT_ALARM" in permissions)
        assertFalse("android.permission.READ_CONTACTS" in permissions)
        assertFalse("android.permission.READ_CALENDAR" in permissions)
        assertFalse("android.permission.RECORD_AUDIO" in permissions)

        assertFalse(runCatching { androidx.work.WorkManager.getInstance() }.isSuccess)
    }

    @Test
    fun realBootReceiverRetainsOnlySyntheticRemindersWhenExactAlarmAccessIsDenied() = runBlocking {
        val alarmManager = targetContext.getSystemService(AlarmManager::class.java)
        assertFalse("Acceptance package must not have exact-alarm access", alarmManager.canScheduleExactAlarms())

        val fixture = UUID.randomUUID().toString()
        val listName = "boot-restore-$fixture"
        val itemId = "boot-restore-$fixture"
        val normalizedLabel = "boot-restore-$fixture"
        val listId = database.listNameDao().insertAndGet(ListNameEntity(name = listName))
        check(listId > 0)
        database.listItemDao().insert(
            ListItemEntity(
                listId = listId,
                text = "synthetic boot restore fixture",
                notificationTime = System.currentTimeMillis() + 60_000,
                itemId = itemId,
            ),
        )
        val tomorrow = LocalDate.now().plusDays(1)
        database.importantDateDao().insert(
            ImportantDateEntity(
                label = "Synthetic boot restore $fixture",
                normalizedLabel = normalizedLabel,
                month = tomorrow.monthValue,
                day = tomorrow.dayOfMonth,
                year = tomorrow.year,
            ),
        )

        try {
            val output = instrumentation.uiAutomation.executeShellCommand(
                "am broadcast -W -a ${BootRestoreAcceptanceReceiver.ACTION_TEST_BOOT_RESTORE} " +
                    "-n $TARGET_APPLICATION_ID/com.kernel.ai.bootrestore.BootRestoreAcceptanceReceiver",
            ).let { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
            }
            assertTrue(
                "Test broadcast did not wait for receiver completion: $output",
                "Broadcast completed" in output,
            )
            assertNotNull(database.listItemDao().getByItemId(itemId))
            assertNotNull(database.importantDateDao().findByNormalizedLabel(normalizedLabel))
        } finally {
            database.listItemDao().getByItemId(itemId)?.let { database.listItemDao().deleteItem(it.id) }
            database.listNameDao().deleteById(listId)
            database.importantDateDao().deleteByNormalizedLabel(normalizedLabel)
        }
    }

    private companion object {
        const val TARGET_APPLICATION_ID = "com.kernel.ai.debug.bootrestore"
    }
}
