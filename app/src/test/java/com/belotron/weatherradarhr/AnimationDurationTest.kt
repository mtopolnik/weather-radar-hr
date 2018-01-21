package com.belotron.weatherradarhr

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameter
import org.junit.runners.Parameterized.Parameters
import org.mockito.Matchers.anyString
import org.mockito.Matchers.eq
import org.mockito.Mockito
import org.mockito.Mockito.mock
import kotlin.coroutines.experimental.buildSequence

@RunWith(Parameterized::class)
class AnimationDurationTest {
    @Parameter
    lateinit var frameDelay: String

    @Parameter(1)
    lateinit var freezeTime: String

    lateinit var prefs: SharedPreferences

    companion object {
        @JvmStatic
        @Parameters(name = "frameDelay={0}, freezeTime={1}")
        fun parameters() = buildSequence {
            for (frameDelay in 0..2)
                for (freeze in 0..2)
                    yield(arrayOf("frameDelay$frameDelay", "freeze$freeze"))
        }.toList()
    }

    @Before
    fun before() {
        prefs = setupPrefs(frameDelay, freezeTime)
    }

    @Test
    fun test() {
        assertEquals(prefs.animationDuration(0), prefs.animationDuration(1))
    }

    private fun SharedPreferences.animationDuration(i: Int) = ImgContext(images[i], this).animationDuration

    private fun setupPrefs(frameDelay: String, freezeTime: String): SharedPreferences {
        val prefs: SharedPreferences = mock(SharedPreferences::class.java)
        Mockito.`when`(prefs.getString(eq("freeze_time"), anyString()))
                .thenReturn(freezeTime)
        Mockito.`when`(prefs.getString(eq("frame_delay"), anyString()))
                .thenReturn(frameDelay)
        return prefs
    }
}
