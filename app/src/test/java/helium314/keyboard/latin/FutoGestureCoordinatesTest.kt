// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import kotlin.test.Test
import kotlin.test.assertEquals

class FutoGestureCoordinatesTest {
    @Test
    fun acceptedTrailMarginsStayInsideFutoModelRange() {
        assertEquals(0f, normalizedGestureCoordinate(-12, 100f))
        assertEquals(1f, normalizedGestureCoordinate(112, 100f))
        assertEquals(0f, normalizedGestureCoordinate(-15, 100f, 4f / 3f))
        assertEquals(1f, normalizedGestureCoordinate(90, 100f, 4f / 3f))
        assertEquals(.5f, normalizedGestureCoordinate(50, 100f))
    }
}
