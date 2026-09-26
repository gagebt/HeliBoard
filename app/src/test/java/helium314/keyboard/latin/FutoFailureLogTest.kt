// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** X12: a FUTO failure is logged with its phase and class, once per distinct cause, never with text. */
class FutoFailureLogTest {
    @Test
    fun logsClassAndPhaseOncePerCauseWithoutText() {
        val lines = mutableListOf<String>()
        val log = FutoFailureLog { lines.add(it) }

        log.report("recognize", IllegalStateException("typed secret words"))
        log.report("recognize", IllegalStateException("other typed words"))
        log.report("predict", IllegalStateException("typed secret words"))
        log.report("recognize", IllegalArgumentException("typed secret words"))

        assertEquals(3, lines.size)
        assertTrue(lines[0].contains("phase=recognize"))
        assertTrue(lines[0].contains("class=java.lang.IllegalStateException"))
        assertTrue(lines[1].contains("phase=predict"))
        assertTrue(lines[2].contains("class=java.lang.IllegalArgumentException"))
        lines.forEach {
            assertFalse(it, it.contains("typed"))
            assertFalse(it, it.contains("\tat "))
        }
    }
}
