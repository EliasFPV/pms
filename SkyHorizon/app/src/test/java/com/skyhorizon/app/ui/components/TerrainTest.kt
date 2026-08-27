package com.skyhorizon.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TerrainTest {

    private val maxHeight = 9.0f
    private val profile = buildTerrain(seed = 1741, maxHeightDeg = maxHeight)

    @Test
    fun `profile stays inside the requested height range`() {
        var lowest = Float.MAX_VALUE
        var highest = -Float.MAX_VALUE
        var azimuth = 0.0
        while (azimuth < 360.0) {
            val h = profile.heightAt(azimuth)
            assertTrue("height $h out of range at $azimuth", h in 0f..maxHeight + 1e-3f)
            if (h < lowest) lowest = h
            if (h > highest) highest = h
            azimuth += 0.1
        }
        // A skyline that never rises or never dips would be a bug, not a mountain range.
        assertTrue("peak too low: $highest", highest > maxHeight * 0.9f)
        assertTrue("valleys too high: $lowest", lowest < maxHeight * 0.1f)
    }

    @Test
    fun `profile joins seamlessly across the north point`() {
        val justBefore = profile.heightAt(359.99)
        val atNorth = profile.heightAt(0.0)
        val justAfter = profile.heightAt(0.01)
        assertEquals(atNorth, justBefore, 0.01f)
        assertEquals(atNorth, justAfter, 0.01f)
    }

    @Test
    fun `profile is continuous everywhere`() {
        var previous = profile.heightAt(0.0)
        var azimuth = 0.05
        var largestJump = 0f
        while (azimuth <= 360.0) {
            val current = profile.heightAt(azimuth)
            largestJump = maxOf(largestJump, abs(current - previous))
            previous = current
            azimuth += 0.05
        }
        assertTrue("discontinuity of $largestJump degrees", largestJump < 0.2f)
    }

    @Test
    fun `negative and wrapped azimuths resolve to the same ridge`() {
        assertEquals(profile.heightAt(37.5), profile.heightAt(397.5), 1e-4f)
        assertEquals(profile.heightAt(37.5), profile.heightAt(-322.5), 1e-4f)
    }

    @Test
    fun `different seeds give different ranges`() {
        val other = buildTerrain(seed = 90210, maxHeightDeg = maxHeight)
        var differences = 0
        var azimuth = 0.0
        while (azimuth < 360.0) {
            if (abs(profile.heightAt(azimuth) - other.heightAt(azimuth)) > 0.5f) differences++
            azimuth += 1.0
        }
        assertTrue("ranges look identical", differences > 180)
    }
}
