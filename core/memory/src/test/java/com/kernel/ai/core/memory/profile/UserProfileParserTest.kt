package com.kernel.ai.core.memory.profile

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class UserProfileParserTest {

    // ── Name extraction ──────────────────────────────────────────────────────

    @Nested
    inner class NameExtraction {
        @Test
        fun `extracts name from 'My name is X'`() {
            val result = UserProfileParser.parse("My name is Nick. I live in Auckland.")
            assertEquals("Nick", result.name)
        }

        @Test
        fun `extracts name from 'I'm X' where X is a proper name`() {
            val result = UserProfileParser.parse("I'm Nick Monrad. I like coffee.")
            assertEquals("Nick Monrad", result.name)
        }

        @Test
        fun `extracts name from 'Call me X'`() {
            val result = UserProfileParser.parse("Call me Dave. I prefer dark mode.")
            assertEquals("Dave", result.name)
        }

        @Test
        fun `extracts name from informal 'X here' pattern`() {
            // B4: "Nick here" was missed before
            val result = UserProfileParser.parse("Nick here, I'm a dev in NZ. Please be concise.")
            assertEquals("Nick", result.name)
        }

        @Test
        fun `does not extract article as name from role sentence`() {
            // B1: "I'm an Android developer" was incorrectly yielding name="an Android"
            val result = UserProfileParser.parse("I'm an Android developer working on AI.")
            assertNull(result.name)
        }

        @Test
        fun `does not extract role keyword as name`() {
            // B1 variant: "I'm a developer" must not produce name="a"
            val result = UserProfileParser.parse("I'm a software developer. I use Linux.")
            assertNull(result.name)
        }

        @Test
        fun `returns null when no name pattern found`() {
            val result = UserProfileParser.parse("I use a Samsung S23 Ultra.")
            assertNull(result.name)
        }
    }

    // ── Role extraction ───────────────────────────────────────────────────────

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

        @Test
        fun `extracts role with technologist keyword`() {
            // B5: "Technologist" was not in ROLE_KEYWORDS
            val result = UserProfileParser.parse("I am a Principal Technologist at LAB3.")
            assertEquals("Principal Technologist at LAB3", result.role)
        }

        @Test
        fun `extracts role for abbreviated dev keyword`() {
            // B5: "dev" was not in ROLE_KEYWORDS
            val result = UserProfileParser.parse("I'm a dev based in Wellington. I prefer dark mode.")
            assertEquals("dev", result.role)
        }

        @Test
        fun `strips location suffix from role when on same sentence`() {
            // B2: "developer based in Auckland" should yield role="developer"
            val result = UserProfileParser.parse("I'm a software developer based in Auckland.")
            assertEquals("software developer", result.role)
            assertEquals("Auckland", result.location)
        }
    }

    // ── Location extraction ───────────────────────────────────────────────────

    @Nested
    inner class LocationExtraction {
        @Test
        fun `preserves comma-separated city and country in Location label`() {
            // B7: "Location: Brisbane, QLD, Australia" was truncated to "Brisbane" at the first comma
            val result = UserProfileParser.parse("Location: Brisbane, QLD, Australia. I use Linux.")
            assertEquals("Brisbane, QLD, Australia", result.location)
        }

        @Test
        fun `extracts location from 'based in X' without capturing relative clause`() {
            // B3: "based in Wellington who works on mobile apps" was capturing the relative clause
            val result = UserProfileParser.parse("I'm a developer based in Wellington who works on mobile apps.")
            assertEquals("Wellington", result.location)
        }

        @Test
        fun `extracts location from 'based in X'`() {
            val result = UserProfileParser.parse("I'm Nick. Based in Auckland, New Zealand. I use a Mac.")
            assertEquals("Auckland, New Zealand", result.location)
        }

        @Test
        fun `extracts location from 'I live in X'`() {
            val result = UserProfileParser.parse("My name is Sara. I live in Melbourne, Australia.")
            assertEquals("Melbourne, Australia", result.location)
        }
    }

    // ── Environment extraction ────────────────────────────────────────────────

    @Nested
    inner class EnvironmentExtraction {
        @Test
        fun `extracts I use patterns`() {
            val result = UserProfileParser.parse("I use a Samsung S23 Ultra. I use Home Assistant for solar.")
            assertEquals(2, result.environment.size)
            assertTrue(result.environment[0].contains("Samsung"))
        }

        @Test
        fun `extracts section-header Systems pattern`() {
            // B8: "Systems: CachyOS (Main PC)" was falling to context
            val result = UserProfileParser.parse("Systems: CachyOS (Main PC), Bazzite OS (ROG Ally).")
            assertTrue(result.environment.isNotEmpty(), "Systems: should go to environment")
        }

        @Test
        fun `extracts section-header Homelab pattern`() {
            val result = UserProfileParser.parse("Homelab: Extensive Docker environment (Plex, Nextcloud).")
            assertTrue(result.environment.isNotEmpty(), "Homelab: should go to environment")
        }
    }

    // ── Rule extraction ───────────────────────────────────────────────────────

    @Nested
    inner class RuleExtraction {
        @Test
        fun `extracts preference patterns`() {
            val result = UserProfileParser.parse("I prefer concise answers. Always use dark mode.")
            assertEquals(2, result.rules.size)
        }

        @Test
        fun `extracts 'Do not' imperative rule`() {
            // B6: "Do not try to inject..." was falling to context
            val result = UserProfileParser.parse("Do not try to inject meal planning advice unless asked.")
            assertTrue(result.rules.isNotEmpty(), "'Do not' should be a rule")
        }

        @Test
        fun `extracts Tone section as rule`() {
            // B6: "Tone: Prefers concise..." was falling to context
            val result = UserProfileParser.parse("Tone: Prefers concise, technically precise, and actionable information.")
            assertTrue(result.rules.isNotEmpty(), "Tone: section should go to rules")
        }

        @Test
        fun `extracts third-person Prefers as rule`() {
            val result = UserProfileParser.parse("Prefers local-first suggestions over cloud dependencies.")
            assertTrue(result.rules.isNotEmpty(), "Third-person Prefers should be a rule")
        }
    }

    // ── Real-world profile (#509) ─────────────────────────────────────────────

    @Nested
    inner class RealWorldProfile {
        private val nick509Profile = """
            my name is Nick
            I am a Principal Technologist at LAB3. High technical literacy.
            Location: Brisbane, QLD, Australia. originally from nz
            Family: Married with three children (ages 1 (Lachlan), 5 (Freyja), and 10(Emilie)) and a Hungarian Vizsla dog named Xena.
            Technical Environment
            Systems: CachyOS (Main PC), Bazzite OS (ROG Ally). Windows 11 (Homelab/ Plex Server, other Docker, *arr stack)
            Hardware: Main PC: AMD Ryzen 5700X3D | Radeon RX 9070 XT | 32GB RAM, HomeLab: Ryzen 5600 | GTX 1060 | 32GB RAM
            Network: Static IP; avoids dynamic DNS dependencies.
            Homelab: Extensive Docker environment (Plex, *arr stack, Nginx). Currently migrating from Google services to self-hosted alternatives like Plexamp, Nextcloud.
            Local AI: Uses llama.cpp, and OpenCode. Prioritizes local-first compute and privacy.
            Smart Home: Advanced Home Assistant user (YAML focus). Integrates Fox ESS solar/battery and multi-zone climate control.
            Gaming: PC-centric (e.g., Cyberpunk 2077, The Witcher 3). Also has a PS5 and plays ARPGs like Ghost of Yotei.
            Cooking: Strong preference for RecipeTin Eats (Nagi's recipes) for meal planning.
            Tone: Prefers concise, technically precise, and actionable information.
            AI Instruction Hook: Assume expert-level knowledge of Linux, containerization, and AI hardware optimization.
            Prioritize local-first suggestions over cloud dependencies. When providing recipes, default to RecipeTin Eats. Do not try to inject meal planning advice unless asked.
        """.trimIndent()

        @Test
        fun `extracts name`() {
            val result = UserProfileParser.parse(nick509Profile)
            assertEquals("Nick", result.name)
        }

        @Test
        fun `extracts role as Principal Technologist`() {
            val result = UserProfileParser.parse(nick509Profile)
            assertTrue(result.role?.contains("Technologist") == true, "role was: ${UserProfileParser.parse(nick509Profile).role}")
        }

        @Test
        fun `extracts location preserving QLD and Australia`() {
            val result = UserProfileParser.parse(nick509Profile)
            assertTrue(result.location?.contains("Brisbane") == true && result.location?.contains("Australia") == true,
                "location was: ${result.location}")
        }

        @Test
        fun `has non-empty environment`() {
            val result = UserProfileParser.parse(nick509Profile)
            assertTrue(result.environment.isNotEmpty(), "environment was empty")
        }

        @Test
        fun `Prioritize and When providing go to rules`() {
            val result = UserProfileParser.parse(nick509Profile)
            val rulesText = result.rules.joinToString(" ")
            assertTrue(rulesText.contains("local-first", ignoreCase = true) ||
                rulesText.contains("RecipeTin", ignoreCase = true),
                "rules were: ${result.rules}")
        }

        @Test
        fun `Tone section goes to rules not context`() {
            val result = UserProfileParser.parse(nick509Profile)
            val inRules = result.rules.any { it.contains("concise", ignoreCase = true) }
            val inContext = result.context.any { it.contains("Tone:", ignoreCase = true) }
            assertTrue(inRules, "Tone: entry should be in rules, not context. rules=${result.rules}")
            assertTrue(!inContext, "Tone: should not be in context")
        }
    }

    // ── Full profile ──────────────────────────────────────────────────────────

    @Nested
    inner class FullProfile {
        @Test
        fun `parses realistic profile`() {
            val text = """
                My name is Nick. I'm a Kotlin developer. Based in Auckland, New Zealand.
                I use a Samsung S23 Ultra. I use Home Assistant for solar monitoring.
                I prefer concise code. Never use Java when Kotlin is available.
                I work on an Android AI assistant called Kernel.
            """.trimIndent()
            val result = UserProfileParser.parse(text)
            assertEquals("Nick", result.name)
            assertEquals("Kotlin developer", result.role)
            assertEquals("Auckland, New Zealand", result.location)
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
                location = "Auckland",
                environment = listOf("Samsung S23"),
                context = listOf("Works on Kernel AI"),
                rules = listOf("Prefer concise code"),
            )
            val json = original.toJson()
            assertTrue(json.contains("\"name\":\"Nick\""))
            assertTrue(json.contains("\"role\":\"developer\""))
            assertTrue(json.contains("\"location\":\"Auckland\""))
            assertTrue(json.contains("\"environment\":[\"Samsung S23\"]"))
        }

        @Test
        fun `toYaml produces compact format`() {
            val profile = UserProfileYaml(
                name = "Nick",
                role = "Kotlin developer",
                location = "Auckland",
                rules = listOf("Prefer concise code"),
            )
            val yaml = profile.toYaml()
            assertTrue(yaml.contains("name: Nick"))
            assertTrue(yaml.contains("role: Kotlin developer"))
            assertTrue(yaml.contains("location: Auckland"))
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
