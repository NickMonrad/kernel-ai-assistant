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
}
