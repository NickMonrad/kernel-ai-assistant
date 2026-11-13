package com.kernel.ai.feature.settings

import android.content.Intent
import android.os.Parcelable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SupportEmailChooserTest {

    private val email: Intent = mockk(relaxed = true)
    private val chooser: Intent = mockk(relaxed = true)

    @BeforeEach
    fun setUp() {
        mockkStatic(Intent::class)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Intent::class)
    }

    private fun target(name: String): Intent = mockk(name = name, relaxed = true)

    @Test
    fun `no email app yields no chooser and never falls back to a generic share sheet`() {
        val result = buildSupportEmailChooser(
            email = email,
            emailPackages = listOf("com.example.share"),
            canHandle = { false },
            targetFor = { target(it) },
        )

        assertNull(result)
        verify(exactly = 0) { Intent.createChooser(any(), any()) }
    }

    @Test
    fun `no email packages at all yields no chooser`() {
        val result = buildSupportEmailChooser(
            email = email,
            emailPackages = emptyList(),
            canHandle = { true },
            targetFor = { target(it) },
        )

        assertNull(result)
        verify(exactly = 0) { Intent.createChooser(any(), any()) }
    }

    @Test
    fun `a single email app is the chooser target with no extra initial targets`() {
        val gmail = target("com.google.android.gm")
        every { Intent.createChooser(gmail, SUPPORT_EMAIL_CHOOSER_TITLE) } returns chooser

        val result = buildSupportEmailChooser(
            email = email,
            emailPackages = listOf("com.google.android.gm"),
            canHandle = { true },
            targetFor = { gmail },
        )

        assertSame(chooser, result)
        verify(exactly = 0) { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, any<Array<out Parcelable>>()) }
    }

    @Test
    fun `several email apps open the first as target and list the rest as initial targets`() {
        val gmail = target("com.google.android.gm")
        val proton = target("ch.protonmail.android")
        val byPackage = mapOf("com.google.android.gm" to gmail, "ch.protonmail.android" to proton)
        every { Intent.createChooser(gmail, SUPPORT_EMAIL_CHOOSER_TITLE) } returns chooser
        every { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, any<Array<out Parcelable>>()) } returns chooser

        val result = buildSupportEmailChooser(
            email = email,
            emailPackages = byPackage.keys.toList(),
            canHandle = { true },
            targetFor = { byPackage.getValue(it) },
        )

        assertSame(chooser, result)
        verify {
            chooser.putExtra(
                Intent.EXTRA_INITIAL_INTENTS,
                match<Array<out Parcelable>> { it.toList() == listOf(proton) },
            )
        }
    }

    @Test
    fun `email packages that cannot receive the email are dropped`() {
        val handled = target("ch.protonmail.android")
        val unhandled = target("com.example.notes")
        val byPackage = mapOf("com.example.notes" to unhandled, "ch.protonmail.android" to handled)
        every { Intent.createChooser(handled, SUPPORT_EMAIL_CHOOSER_TITLE) } returns chooser

        val result = buildSupportEmailChooser(
            email = email,
            emailPackages = byPackage.keys.toList(),
            canHandle = { it === handled },
            targetFor = { byPackage.getValue(it) },
        )

        assertSame(chooser, result)
        verify(exactly = 1) { Intent.createChooser(handled, SUPPORT_EMAIL_CHOOSER_TITLE) }
        verify(exactly = 0) { chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, any<Array<out Parcelable>>()) }
    }
}
