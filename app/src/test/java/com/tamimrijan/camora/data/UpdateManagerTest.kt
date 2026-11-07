package com.tamimrijan.camora.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateManagerTest {
    @Test fun newerVersionsAreDetected() {
        assertTrue(UpdateManager.isNewer("2.3.3", "2.3.2"))
        assertTrue(UpdateManager.isNewer("v2.4.0", "2.3.9"))
        assertTrue(UpdateManager.isNewer("2.3.10", "2.3.9"))
        assertTrue(UpdateManager.isNewer("3.0", "2.9.9"))
    }

    @Test fun sameOrOlderIsNotAnUpdate() {
        assertFalse(UpdateManager.isNewer("2.3.2", "2.3.2"))
        assertFalse(UpdateManager.isNewer("v2.3.0", "2.3.2"))
        assertFalse(UpdateManager.isNewer("2.3", "2.3.0"))
    }

    @Test fun releaseNotesBecomePlainText() {
        val md = "## 🌘 Camora 2.3.0\n\n### What's new\n- **Logo:** new\n* item"
        assertEquals("🌘 Camora 2.3.0\nWhat's new\n• Logo: new\n• item", UpdateManager.cleanNotes(md))
    }
}