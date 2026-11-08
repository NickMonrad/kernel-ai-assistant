package com.kernel.ai.core.memory.nextcloud

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.xml.sax.SAXException

@RunWith(AndroidJUnit4::class)
class NextcloudXmlParserAndroidTest {
    @Test
    fun androidSupportsSecureCalDavParsing() {
        val document = parseSecureXml(
            "<d:multistatus xmlns:d=\"DAV:\"><d:response/></d:multistatus>",
        )

        assertEquals("multistatus", document.documentElement.localName)
    }

    @Test
    fun androidRejectsDoctypeBeforeParserConfiguration() {
        val error = runCatching {
            parseSecureXml(
                "<!DOCTYPE d:multistatus [<!ENTITY injected \"unsafe\">]>" +
                    "<d:multistatus xmlns:d=\"DAV:\"><d:response>&injected;</d:response></d:multistatus>",
            )
        }.exceptionOrNull()

        assertTrue(error is SAXException)
    }
}
