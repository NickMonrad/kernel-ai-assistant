package com.kernel.ai.navigation

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [ShortcutRegistry] completeness and validity.
 *
 * Ensures every registered shortcut has a stable ID, label, icon, and route.
 * Default drawer shortcuts must remain present and Settings must be pinned
 * separately.
 */
class ShortcutRegistryTest {

    @Nested
    @DisplayName("Default drawer shortcuts")
    inner class DefaultDrawerShortcuts {

        @Test
        fun `all defaults are present`() {
            val expectedIds = setOf(
                "lists", "notes", "meal_plans",
                "clock", "important_dates", "people_contacts", "convert",
            )
            val actualIds = ShortcutRegistry.drawerDefaults.map { it.id }.toSet()
            assertEquals(expectedIds, actualIds, "Drawer defaults must include all expected shortcuts")
        }

        @Test
        fun `every default has stable ID, label, icon, and route`() {
            for (shortcut in ShortcutRegistry.drawerDefaults) {
                assertTrue(shortcut.id.isNotBlank(), "ID must not be blank: ${shortcut.id}")
                assertTrue(shortcut.label.isNotBlank(), "Label must not be blank: ${shortcut.id}")
                assertNotNull(shortcut.icon, "Icon must not be null: ${shortcut.id}")
                assertTrue(shortcut.route.isNotBlank(), "Route must not be blank: ${shortcut.id}")
            }
        }

        @Test
        fun `every default has canFavourite true`() {
            for (shortcut in ShortcutRegistry.drawerDefaults) {
                assertTrue(shortcut.canFavourite, "Default should be favourite-eligible: ${shortcut.id}")
            }
        }

        @Test
        fun `every default can record recent`() {
            for (shortcut in ShortcutRegistry.drawerDefaults) {
                assertTrue(shortcut.canRecordRecent, "Default should record recents: ${shortcut.id}")
            }
        }
    }

    @Nested
    @DisplayName("Settings shortcut")
    inner class SettingsShortcut {

        @Test
        fun `settings is in the registry`() {
            val settings = ShortcutRegistry.byId("settings")
            assertNotNull(settings)
            assertEquals("settings", settings?.id)
            assertEquals("Settings", settings?.label)
        }

        @Test
        fun `settings is not favourite-eligible`() {
            val settings = ShortcutRegistry.byId("settings")
            assertFalse(settings?.canFavourite ?: true, "Settings must not be favourite-eligible")
        }

        @Test
        fun `settings does not record recents`() {
            val settings = ShortcutRegistry.byId("settings")
            assertFalse(settings?.canRecordRecent ?: true, "Settings must not record recents")
        }

        @Test
        fun `settings is marked as settings`() {
            val settings = ShortcutRegistry.byId("settings")
            assertTrue(settings?.isSettings ?: false, "Settings must be marked isSettings")
        }

        @Test
        fun `settings cannot record recent even via canRecordRecent`() {
            val settings = ShortcutRegistry.byId("settings")
            assertFalse(settings?.canRecordRecent ?: true, "Settings must have canRecordRecent = false")
        }
    }

    @Nested
    @DisplayName("Registry resolution")
    inner class RegistryResolution {

        @Test
        fun `known ID resolves to shortcut`() {
            for (id in listOf("lists", "notes", "clock", "convert", "settings")) {
                assertNotNull(ShortcutRegistry.byId(id), "Should resolve known ID: $id")
            }
        }

        @Test
        fun `unknown ID returns null`() {
            assertNull(ShortcutRegistry.byId("nonexistent"))
            assertNull(ShortcutRegistry.byId(""))
            assertNull(ShortcutRegistry.byId("   "))
        }

        @Test
        fun `allFavouriteEligibleIds includes defaults but not settings`() {
            for (id in ShortcutRegistry.drawerDefaults.map { it.id }) {
                assertTrue(id in ShortcutRegistry.allFavouriteEligibleIds, "Default should be eligible: $id")
            }
            assertFalse("settings" in ShortcutRegistry.allFavouriteEligibleIds, "Settings must not be in eligible set")
        }

        @Test
        fun `sub-feature shortcuts are resolvable`() {
            assertNotNull(ShortcutRegistry.byId("clock.stopwatch"))
            assertNotNull(ShortcutRegistry.byId("clock.timer"))
            assertNotNull(ShortcutRegistry.byId("clock.alarms"))
        }

        @Test
        fun `clock stopwatch route contains stopwatch tab param`() {
            val shortcut = ShortcutRegistry.byId("clock.stopwatch")
            assertNotNull(shortcut)
            assertTrue(shortcut?.route?.contains("?tab=stopwatch") == true,
                "clock.stopwatch route should contain ?tab=stopwatch, got: ${shortcut?.route}")
        }

        @Test
        fun `clock timer route contains timer tab param`() {
            val shortcut = ShortcutRegistry.byId("clock.timer")
            assertNotNull(shortcut)
            assertTrue(shortcut?.route?.contains("?tab=timer") == true,
                "clock.timer route should contain ?tab=timer, got: ${shortcut?.route}")
        }

        @Test
        fun `clock alarms route contains alarms tab param`() {
            val shortcut = ShortcutRegistry.byId("clock.alarms")
            assertNotNull(shortcut)
            assertTrue(shortcut?.route?.contains("?tab=alarms") == true,
                "clock.alarms route should contain ?tab=alarms, got: ${shortcut?.route}")
        }

        @Test
        fun `clock top-level route does NOT contain tab param`() {
            val shortcut = ShortcutRegistry.byId("clock")
            assertNotNull(shortcut)
            assertFalse(shortcut?.route?.contains("?tab=") == true,
                "clock route should NOT contain ?tab=, got: ${shortcut?.route}")
        }
    }

    @Nested
    @DisplayName("All shortcuts")
    inner class AllShortcuts {

        @Test
        fun `all shortcuts have valid metadata`() {
            for ((id, shortcut) in ShortcutRegistry.allById) {
                assertTrue(shortcut.id.isNotBlank(), "ID must not be blank for key: $id")
                assertTrue(shortcut.label.isNotBlank(), "Label must not be blank for: $id")
                assertNotNull(shortcut.icon, "Icon must not be null for: $id")
                assertTrue(shortcut.route.isNotBlank(), "Route must not be blank for: $id")
            }
        }

        @Test
        fun `allById contains every top-level and sub-feature shortcut`() {
            val expected = setOf(
                "lists", "notes", "meal_plans",
                "clock", "important_dates", "people_contacts", "convert",
                "settings",
                "clock.stopwatch", "clock.timer", "clock.alarms",
            )
            assertEquals(expected, ShortcutRegistry.allById.keys)
        }
    }

    @Nested
    @DisplayName("Drawer item ordering")
    inner class DrawerOrdering {

        @Test
        fun `buildDrawerItems places favourites first`() {
            val items = buildDrawerItems(
                favouriteIds = listOf("notes", "lists"),
                recentIds = emptyList(),
            )
            assertEquals("notes", items[0].id, "First item should be first favourite")
            assertEquals("lists", items[1].id, "Second item should be second favourite")
        }

        @Test
        fun `buildDrawerItems places recents after favourites`() {
            val items = buildDrawerItems(
                favouriteIds = listOf("lists"),
                recentIds = listOf("convert"),
            )
            assertTrue(items.indexOfFirst { it.id == "convert" } > items.indexOfFirst { it.id == "lists" },
                "Recents should appear after favourites")
        }

        @Test
        fun `buildDrawerItems dedupes recents that are already favourites`() {
            val items = buildDrawerItems(
                favouriteIds = listOf("clock"),
                recentIds = listOf("clock", "convert"),
            )
            val clockCount = items.count { it.id == "clock" }
            assertEquals(1, clockCount, "Clock should appear only once even if in both favs and recents")
        }

        @Test
        fun `buildDrawerItems fills remaining with defaults`() {
            val items = buildDrawerItems(
                favouriteIds = emptyList(),
                recentIds = emptyList(),
            )
            // All defaults should be present
            for (def in ShortcutRegistry.drawerDefaults) {
                assertTrue(items.any { it.id == def.id }, "Default should be present: ${def.id}")
            }
        }

        @Test
        fun `buildDrawerItems places settings last`() {
            val items = buildDrawerItems(
                favouriteIds = listOf("lists"),
                recentIds = listOf("convert"),
            )
            assertEquals("settings", items.last().id, "Settings must be the last item")
            assertTrue(items.last().isSettings, "Last item must be marked isSettings")
        }

        @Test
        fun `buildDrawerItems excludes settings from favourites`() {
            val items = buildDrawerItems(
                favouriteIds = listOf("settings"),
                recentIds = emptyList(),
            )
            // Settings should not appear from favourites; only once at the end
            val settingsPositions = items.mapIndexedNotNull { i, s -> i.takeIf { s.id == "settings" } }
            assertEquals(1, settingsPositions.size, "Settings should appear exactly once")
            assertEquals(items.lastIndex, settingsPositions[0], "Settings should be at the last position")
        }
    }
}
