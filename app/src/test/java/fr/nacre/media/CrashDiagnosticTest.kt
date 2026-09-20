package fr.nacre.media

import org.junit.Assert.*
import org.junit.Test

class CrashDiagnosticTest {
    @Test fun preservesCauseAndFramesWithoutLeakingExceptionMessages() {
        val cause = IllegalArgumentException("https://server.test/?apikey=SECRET")
        val report = crashStack(IllegalStateException("magnet:?private=SECRET", cause))
        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("java.lang.IllegalArgumentException"))
        assertTrue(report.contains("CrashDiagnosticTest"))
        assertFalse(report.contains("SECRET"))
        assertFalse(report.contains("magnet:"))
    }
    @Test fun cyclicCausesDoNotLoop() {
        val first = RuntimeException()
        val second = RuntimeException(first)
        first.initCause(second)
        assertTrue(crashStack(first).length < 24000)
    }
}
