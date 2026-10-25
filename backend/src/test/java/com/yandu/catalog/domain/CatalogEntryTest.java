package com.yandu.catalog.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CatalogEntryTest {

    private static final long ENTRY_ID = 101L;
    private static final long PROJECT_ID = 201L;

    @Test
    void changingSourceTextCreatesANewRevisionAndEvent() {
        var entry = new CatalogEntry(ENTRY_ID, PROJECT_ID, "auth.login", "Sign in", 1, 1);

        var change = entry.changeSource(1, "Log in");

        assertEquals("Log in", entry.sourceText());
        assertEquals(2, entry.sourceRevision());
        assertEquals(2, entry.version());
        assertEquals(1, change.previousRevision());
        assertEquals(2, change.currentRevision());
        assertTrue(change.changed());
    }

    @Test
    void unchangedSourceTextDoesNotCreateARevisionOrEvent() {
        var entry = new CatalogEntry(ENTRY_ID, PROJECT_ID, "auth.login", "Sign in", 4, 7);

        var change = entry.changeSource(4, "Sign in");

        assertFalse(change.changed());
        assertFalse(change.changed());
        assertEquals(4, entry.sourceRevision());
        assertEquals(7, entry.version());
        assertEquals("Sign in", entry.sourceText());
    }

    @Test
    void staleRevisionIsRejectedInsteadOfOverwritingNewerSource() {
        var entry = new CatalogEntry(ENTRY_ID, PROJECT_ID, "auth.login", "Sign in", 3, 5);

        assertThrows(CatalogEntry.SourceRevisionConflictException.class,
                () -> entry.changeSource(2, "Log in"));

        assertEquals("Sign in", entry.sourceText());
        assertEquals(3, entry.sourceRevision());
        assertEquals(5, entry.version());
    }

    @Test
    void blankSourceTextIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogEntry(ENTRY_ID, PROJECT_ID, "auth.login", "  ", 1, 1));
        var entry = new CatalogEntry(ENTRY_ID, PROJECT_ID, "auth.login", "Sign in", 1, 1);
        assertThrows(IllegalArgumentException.class,
                () -> entry.changeSource(1, "\n\t"));
    }

    @Test
    void keyIsTrimmedWithoutChangingCaseAndLimitedToTwoHundredCharacters() {
        var entry = new CatalogEntry(ENTRY_ID, PROJECT_ID, "  Auth.Login  ", "Sign in", 1, 1);

        assertEquals("Auth.Login", entry.key());
        assertThrows(IllegalArgumentException.class,
                () -> new CatalogEntry(ENTRY_ID, PROJECT_ID, "k".repeat(201), "Sign in", 1, 1));
    }
}
