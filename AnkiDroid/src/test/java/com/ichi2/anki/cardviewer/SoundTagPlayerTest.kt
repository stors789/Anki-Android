// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.cardviewer

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import androidx.core.content.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ichi2.anki.R
import com.ichi2.anki.RobolectricTest
import com.ichi2.anki.libanki.SoundOrVideoTag
import com.ichi2.anki.settings.Prefs
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowMediaPlayer
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 36])
class SoundTagPlayerTest : RobolectricTest() {
    private lateinit var player: SoundTagPlayer
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var audioManager: ShadowAudioManager

    @Before
    override fun setUp() {
        super.setUp()
        Prefs.sharedPrefs.edit { remove(targetContext.getString(R.string.audio_focus_enabled_key)) }
        audioManager = shadowOf(targetContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        ShadowMediaPlayer.setMediaInfoProvider { ShadowMediaPlayer.MediaInfo(10_000, 0) }
        ShadowMediaPlayer.setCreateListener { mp, _ -> mediaPlayer = mp }
        player = SoundTagPlayer("file:///collection.media/", mockk())
    }

    @After
    fun releasePlayer() {
        player.release()
    }

    @Test
    fun `audio focus is enabled by default and released on completion`() =
        runTest {
            assertTrue(Prefs.audioFocusEnabled)
            val playback = launch(start = CoroutineStart.UNDISPATCHED) { player.play(SoundOrVideoTag("test.mp3"), null) }
            runCurrent()

            assertTrue(mediaPlayer.isPlaying)
            val request = assertNotNull(audioManager.lastAudioFocusRequest)
            assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.durationHint)
            assertNull(audioManager.lastAbandonedAudioFocusListener)

            shadowOf(mediaPlayer).invokeCompletionListener()
            playback.join()
            assertSame(request.listener, audioManager.lastAbandonedAudioFocusListener)
        }

    @Test
    fun `disabled focus plays audio without requesting or abandoning focus`() =
        runTest {
            Prefs.audioFocusEnabled = false
            audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            val playback = launch(start = CoroutineStart.UNDISPATCHED) { player.play(SoundOrVideoTag("test.mp3"), null) }
            runCurrent()

            assertTrue(mediaPlayer.isPlaying)
            assertNull(audioManager.lastAudioFocusRequest)

            shadowOf(mediaPlayer).invokeCompletionListener()
            playback.join()
            player.release()
            assertNull(audioManager.lastAbandonedAudioFocusListener)
        }

    @Test
    fun `denied focus still prevents playback when enabled`() =
        runTest {
            audioManager.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
            val playback = launch(start = CoroutineStart.UNDISPATCHED) { player.play(SoundOrVideoTag("test.mp3"), null) }
            playback.join()

            assertTrue(playback.isCancelled)
            assertFalse(mediaPlayer.isPlaying)
            assertNotNull(audioManager.lastAudioFocusRequest)
            assertNull(audioManager.lastAbandonedAudioFocusListener)
        }

    @Test
    fun `cancellation releases held focus even after the preference is disabled`() =
        runTest {
            val playback = launch(start = CoroutineStart.UNDISPATCHED) { player.play(SoundOrVideoTag("test.mp3"), null) }
            runCurrent()
            val request = assertNotNull(audioManager.lastAudioFocusRequest)

            Prefs.audioFocusEnabled = false
            playback.cancel()
            playback.join()

            assertFalse(mediaPlayer.isPlaying)
            assertSame(request.listener, audioManager.lastAbandonedAudioFocusListener)
        }
}
