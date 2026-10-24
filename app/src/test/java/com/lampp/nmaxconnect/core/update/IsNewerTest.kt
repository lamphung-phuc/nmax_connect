package com.lampp.nmaxconnect.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsNewerTest {
    @Test fun higherPatchMinorMajorIsNewer() {
        assertTrue(isNewer("1.6.14", "1.6.13"))
        assertTrue(isNewer("1.7.0", "1.6.13"))
        assertTrue(isNewer("2.0", "1.6.13"))
    }

    @Test fun comparesNumbersNotText() = assertTrue(isNewer("1.10.0", "1.9.2"))

    @Test fun sameOrOlderIsNotNewer() {
        assertFalse(isNewer("1.6.13", "1.6.13"))
        assertFalse(isNewer("1.6", "1.6.0"))
        assertFalse(isNewer("1.6.12", "1.6.13"))
    }

    @Test fun ignoresSuffix() = assertFalse(isNewer("1.6.13-beta", "1.6.13"))

    @Test fun notesLoseMarkdown() = assertEquals(
        "What's new\n\n• Faster map with none\n  • nested\nQuoted",
        plainNotes("## What's new\r\n\r\n\r\n- **Faster** map with `none`\n  * nested\n> Quoted\n"),
    )

    private val bilingual = "Intro\n\n## English\n### Dash\n- Faster\n\n## Tiếng Việt\n### Dash\n- Nhanh hơn\n"

    @Test fun notesSplitByLanguageHeading() {
        val notes = notesByLanguage(bilingual)
        assertEquals("Intro\n\nDash\n• Faster", notes["en"])
        assertEquals("Intro\n\nDash\n• Nhanh hơn", notes["vi"])
    }

    @Test fun notesPickTheAppLanguageElseEnglish() {
        val update = AppUpdate("1.6.15", notesByLanguage(bilingual), "")
        assertEquals("Intro\n\nDash\n• Nhanh hơn", update.notesIn("vi"))
        assertEquals("Intro\n\nDash\n• Faster", update.notesIn("fr"))
    }

    @Test fun notesWithoutLanguageHeadingsAreEnglish() = assertEquals(
        mapOf("en" to "What's new\n• Faster"),
        notesByLanguage("## What's new\n- Faster"),
    )
}
