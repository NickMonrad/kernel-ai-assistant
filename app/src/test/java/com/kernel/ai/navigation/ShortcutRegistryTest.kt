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
            assertNotNull(ShortcutRegistry.byId("clock.world_clock"))
            assertNotNull(ShortcutRegistry.byId("convert.currency"))
            assertNotNull(ShortcutRegistry.byId("convert.unit"))
            assertNotNull(ShortcutRegistry.byId("convert.cooking"))
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
        fun `clock world_clock route contains world_clock tab param`() {
            val shortcut = ShortcutRegistry.byId("clock.world_clock")
            assertNotNull(shortcut)
            assertTrue(shortcut?.route?.contains("?tab=world_clock") == true,
                "clock.world_clock route should contain ?tab=world_clock, got: ${shortcut?.route}")
        }

        @Test
        fun `convert currency route contains currency tab param`() {
            val shortcut = ShortcutRegistry.byId("convert.currency")
            assertNotNull(shortcut)
            assertTrue(shortcut?.route?.contains("?tab=currency") == true,
                "convert.currency route should contain ?tab=currency, got: ${shortcut?.route}")
        }

        @Test
        fun `convert unit route contains unit tab param`() {
            val shortcut = ShortcutRegistry.byId("convert.unit")
            assertNotNull(shortcut)
            assertTrue(shortcut?.route?.contains("?tab=unit") == true,
                "convert.unit route should contain ?tab=unit, got: ${shortcut?.route}")
        }

        @Test
        fun `convert cooking route contains cooking tab param`() {
            val shortcut = ShortcutRegistry.byId("convert.cooking")
            assertNotNull(shortcut)
            assertTrue(shortcut?.route?.contains("?tab=cooking") == true,
                "convert.cooking route should contain ?tab=cooking, got: ${shortcut?.route}")
        }

        @Test
        fun `convert top-level route does NOT contain tab param`() {
            val shortcut = ShortcutRegistry.byId("convert")
            assertNotNull(shortcut)
            assertFalse(shortcut?.route?.contains("?tab=") == true,
                "convert route should NOT contain ?tab=, got: ${shortcut?.route}")
        }

        @Test
        fun `clock top-level route does NOT contain tab param`() {
            val shortcut = ShortcutRegistry.byId("clock")
            assertNotNull(shortcut)
            assertFalse(shortcut?.route?.contains("?tab=") == true,
                "clock route should NOT contain ?tab=, got: ${shortcut?.route}")
        }

        @Test
        fun `sub-feature shortcuts are favourite-eligible`() {
            assertTrue(ShortcutRegistry.byId("clock.stopwatch")?.canFavourite ?: false,
                "clock.stopwatch must be favourite-eligible")
            assertTrue(ShortcutRegistry.byId("clock.timer")?.canFavourite ?: false,
                "clock.timer must be favourite-eligible")
            assertTrue(ShortcutRegistry.byId("clock.alarms")?.canFavourite ?: false,
                "clock.alarms must be favourite-eligible")
            assertTrue(ShortcutRegistry.byId("clock.world_clock")?.canFavourite ?: false,
                "clock.world_clock must be favourite-eligible")
            assertTrue(ShortcutRegistry.byId("convert.currency")?.canFavourite ?: false,
                "convert.currency must be favourite-eligible")
            assertTrue(ShortcutRegistry.byId("convert.unit")?.canFavourite ?: false,
                "convert.unit must be favourite-eligible")
            assertTrue(ShortcutRegistry.byId("convert.cooking")?.canFavourite ?: false,
                "convert.cooking must be favourite-eligible")
        }

        @Test
        fun `sub-feature shortcuts are in allFavouriteEligibleIds`() {
            assertTrue("clock.stopwatch" in ShortcutRegistry.allFavouriteEligibleIds)
            assertTrue("clock.timer" in ShortcutRegistry.allFavouriteEligibleIds)
            assertTrue("clock.alarms" in ShortcutRegistry.allFavouriteEligibleIds)
            assertTrue("clock.world_clock" in ShortcutRegistry.allFavouriteEligibleIds)
            assertTrue("convert.currency" in ShortcutRegistry.allFavouriteEligibleIds)
            assertTrue("convert.unit" in ShortcutRegistry.allFavouriteEligibleIds)
            assertTrue("convert.cooking" in ShortcutRegistry.allFavouriteEligibleIds)
        }
    }

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
                "clock.stopwatch", "clock.timer", "clock.alarms", "clock.world_clock",
                "convert.currency", "convert.unit", "convert.cooking",
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

    @Nested
    @DisplayName("No calculator shortcuts")
    inner class NoCalculatorShortcut {

        @Test
        fun `there is no calculator shortcut`() {
            assertNull(ShortcutRegistry.byId("calculator"))
            assertNull(ShortcutRegistry.byId("convert.calculator"))
            assertFalse(ShortcutRegistry.allById.containsKey("calculator"))
            assertFalse(ShortcutRegistry.allById.containsKey("convert.calculator"))
        }

        @Test
        fun `no shortcut contains the word calculator in its label`() {
            for ((id, shortcut) in ShortcutRegistry.allById) {
                assertFalse(
                    shortcut.label.contains("calculator", ignoreCase = true),
                    "Shortcut $id must not mention calculator: ${shortcut.label}",
                )
            }
        }
    }

    @Nested
    @DisplayName("Drawer sections")
    inner class DrawerSections {

        @Test
        fun `buildDrawerSections places favourites in first section`() {
            val sections = buildDrawerSections(
                favouriteIds = listOf("notes", "lists"),
                recentIds = emptyList(),
            )
            assertEquals("Favourites", sections[0].header)
            assertEquals(listOf("notes", "lists"), sections[0].items.map { it.id })
        }

        @Test
        fun `buildDrawerSections places non-favourite recents in second section`() {
            val sections = buildDrawerSections(
                favouriteIds = listOf("lists"),
                recentIds = listOf("convert"),
            )
            assertEquals("Recently Used", sections[1].header)
            assertEquals(listOf("convert"), sections[1].items.map { it.id })
        }

        @Test
        fun `buildDrawerSections dedupes recents against favourites`() {
            val sections = buildDrawerSections(
                favouriteIds = listOf("clock"),
                recentIds = listOf("clock", "convert"),
            )
            assertEquals(listOf("convert"), sections[1].items.map { it.id })
        }

        @Test
        fun `buildDrawerSections places remaining defaults in third section`() {
            val sections = buildDrawerSections(
                favouriteIds = emptyList(),
                recentIds = emptyList(),
            )
            assertEquals("More Shortcuts", sections[2].header)
            for (def in ShortcutRegistry.drawerDefaults) {
                assertTrue(sections[2].items.any { it.id == def.id }, "Default should be in More Shortcuts: ${def.id}")
            }
        }

        @Test
        fun `buildDrawerSections places settings in last section with null header`() {
            val sections = buildDrawerSections(
                favouriteIds = listOf("lists"),
                recentIds = emptyList(),
            )
            val lastSection = sections.last()
            assertNull(lastSection.header, "Settings section header should be null")
            assertEquals(listOf("settings"), lastSection.items.map { it.id })
            assertTrue(lastSection.items.single().isSettings, "Last item must be settings")
        }

        @Test
        fun `buildDrawerSections has four sections`() {
            val sections = buildDrawerSections(
                favouriteIds = emptyList(),
                recentIds = emptyList(),
            )
            assertEquals(4, sections.size)
            assertEquals("Favourites", sections[0].header)
            assertEquals("Recently Used", sections[1].header)
            assertEquals("More Shortcuts", sections[2].header)
            assertNull(sections[3].header)
        }

        @Test
        fun `buildDrawerSections shows empty message when no favourites`() {
            val sections = buildDrawerSections(
                favouriteIds = emptyList(),
                recentIds = listOf("convert"),
            )
            assertNotNull(sections[0].emptyMessage, "Favourites section should have empty message")
            assertTrue(sections[0].items.isEmpty(), "Favourites should be empty")
        }

        @Test
        fun `buildDrawerSections does not show empty message when favourites exist`() {
            val sections = buildDrawerSections(
                favouriteIds = listOf("notes"),
                recentIds = emptyList(),
            )
            assertNull(sections[0].emptyMessage, "Favourites section should not have empty message when items exist")
            assertTrue(sections[0].items.isNotEmpty(), "Favourites should have items")
        }

        @Test
        fun `buildDrawerSections dedupes sub-feature favourites`() {
            val sections = buildDrawerSections(
                favouriteIds = listOf("clock.stopwatch", "clock.timer", "clock.alarms"),
                recentIds = listOf("clock.stopwatch"),
            )
            assertEquals(3, sections[0].items.size)
            assertTrue(sections[1].items.isEmpty(), "Recents should not contain already-favourited items")
        }
    }

    @Nested
    @DisplayName("Route helpers")
    inner class RouteHelpers {

        @Test
        fun `buildConvertTabRoute produces correct route`() {
            assertEquals("convert?tab=currency", buildConvertTabRoute("currency"))
            assertEquals("convert?tab=unit", buildConvertTabRoute("unit"))
            assertEquals("convert?tab=cooking", buildConvertTabRoute("cooking"))
        }

        @Test
        fun `buildSidePanelTabRoute produces correct routes`() {
            assertEquals("settings/side_panel?tab=stopwatch", buildSidePanelTabRoute("stopwatch"))
            assertEquals("settings/side_panel?tab=world_clock", buildSidePanelTabRoute("world_clock"))
        }
    }
}
