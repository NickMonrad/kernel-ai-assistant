package com.kernel.ai.core.memory.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UserProfileParserTest {

    @Nested
    inner class NameExtraction {
        @Test
        fun `extracts name from 'My name is X'`() {
            val result = UserProfileParser.parse("My name is Nick. I live in Auckland.")
            assertEquals("Nick", result.name)
        }

        @Test
        fun `extracts name from 'I'm X'`() {
            val result = UserProfileParser.parse("I'm Nick Monrad. I like coffee.")
            assertEquals("Nick Monrad", result.name)
        }

        @Test
        fun `extracts name from 'Call me X'`() {
            val result = UserProfileParser.parse("Call me Dave. I prefer dark mode.")
            assertEquals("Dave", result.name)
        }

        @Test
        fun `returns null when no name pattern found`() {
            val result = UserProfileParser.parse("I use a Samsung S23 Ultra.")
            assertNull(result.name)
        }
    }

    @Nested
    inner class RoleExtraction {
        @Test
        fun `extracts role with developer keyword`() {
            val result = UserProfileParser.parse("My name is Nick. I'm a Kotlin developer.")
            assertEquals("Nick", result.name)
            assertEquals("Kotlin developer", result.role)
        }

        @Test
        fun `extracts role with engineer keyword`() {
            val result = UserProfileParser.parse("I am a software engineer at Google.")
            assertEquals("software engineer at Google", result.role)
        }
    }

    @Nested
    inner class EnvironmentExtraction {
        @Test
        fun `extracts I use patterns`() {
            val result = UserProfileParser.parse("I use a Samsung S23 Ultra. I use Home Assistant for solar.")
            assertEquals(2, result.environment.size)
            assertTrue(result.environment[0].contains("Samsung"))
        }
    }

    @Nested
    inner class RuleExtraction {
        @Test
        fun `extracts preference patterns`() {
            val result = UserProfileParser.parse("I prefer concise answers. Always use dark mode.")
            assertEquals(2, result.rules.size)
        }
    }

    @Nested
    inner class FullProfile {
        @Test
        fun `parses realistic profile`() {
            val text = """
                My name is Nick. I'm a Kotlin developer based in Auckland, New Zealand.
                I use a Samsung S23 Ultra. I use Home Assistant for solar monitoring.
                I prefer concise code. Never use Java when Kotlin is available.
                I work on an Android AI assistant called Kernel.
            """.trimIndent()
            val result = UserProfileParser.parse(text)
            assertEquals("Nick", result.name)
            assertEquals("Kotlin developer based in Auckland, New Zealand", result.role)
            assertTrue(result.environment.isNotEmpty())
            assertTrue(result.rules.isNotEmpty())
        }

        @Test
        fun `empty text returns empty profile`() {
            val result = UserProfileParser.parse("")
            assertTrue(result.isEmpty())
        }
    }

    @Nested
    inner class YamlSerialization {
        @Test
        fun `toJson produces valid JSON`() {
            val original = UserProfileYaml(
                name = "Nick",
                role = "developer",
                environment = listOf("Samsung S23"),
                context = listOf("Works on Kernel AI"),
                rules = listOf("Prefer concise code"),
            )
            val json = original.toJson()
            assertTrue(json.contains("\"name\":\"Nick\""))
            assertTrue(json.contains("\"role\":\"developer\""))
            assertTrue(json.contains("\"environment\":[\"Samsung S23\"]"))
        }

        @Test
        fun `toYaml produces compact format`() {
            val profile = UserProfileYaml(
                name = "Nick",
                role = "Kotlin developer",
                rules = listOf("Prefer concise code"),
            )
            val yaml = profile.toYaml()
            assertTrue(yaml.contains("name: Nick"))
            assertTrue(yaml.contains("role: Kotlin developer"))
            assertTrue(yaml.contains("  - Prefer concise code"))
        }

        @Test
        fun `empty profile produces empty yaml`() {
            val profile = UserProfileYaml()
            assertTrue(profile.isEmpty())
            assertEquals("", profile.toYaml())
        }
    }
}
