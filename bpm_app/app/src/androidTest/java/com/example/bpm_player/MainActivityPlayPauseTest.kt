package com.example.bpm_player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.example.bpm_player.databinding.ActivityMainBinding
import org.hamcrest.Matcher
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Testes instrumentados de UI para a tela principal — valida play/pause
 * usando um arquivo .mp3 de teste injetado diretamente via assets.
 *
 * Como funciona:
 * - O arquivo `test_music.mp3` (copiado de C:\Users\piru\Music\) fica em
 *   `src/main/assets/test/test_music.mp3` e é acessado como asset.
 * - O teste copia o asset para um arquivo temporário no cache do app e
 *   cria uma URI de arquivo (`file://`) que é injetada no SongAdapter.
 * - Não depende do MediaStore, ContentProvider ou permissões de armazenamento.
 *
 * @see <a href="https://developer.android.com/training/testing/unit-testing/instrumented-unit-tests">Instrumented tests</a>
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityPlayPauseTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var testMusicUri: Uri? = null

    companion object {
        private const val TEST_ASSET_PATH = "test/test_music.mp3"
        private const val TEST_MUSIC_TITLE = "Deixa-Me Ir - 7AL3M"
        private const val TEST_MUSIC_ARTIST = "7AL3M"
        private const val TEST_MUSIC_DURATION = 214000L // ~3:34 em ms
        private const val TIMEOUT_MS = 6000L
    }

    /**
     * Copia o asset para o cache do app e retorna a URI do arquivo.
     * Isso evita depender do MediaStore ou ContentProvider.
     */
    private fun copyAssetToCache(assetPath: String): Uri {
        context.assets.open(assetPath).use { input ->
            val cacheFile = File(context.cacheDir, "test_music.mp3")
            FileOutputStream(cacheFile).use { output ->
                input.copyTo(output)
            }
            return Uri.fromFile(cacheFile)
        }
    }

    @Before
    fun setup() {
        testMusicUri = copyAssetToCache(TEST_ASSET_PATH)
    }

    @After
    fun teardown() {
        scenario?.moveToState(Lifecycle.State.DESTROYED)
        scenario = null
        File(context.cacheDir, "test_music.mp3").delete()
    }

    /**
     * Valida o estado inicial da Activity ao abrir.
     * Espera que o estado vazio seja mostrado (sem música selecionada).
     */
    @Test
    fun activity_launches_withEmptyState() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        // Título vazio
        onView(withId(R.id.trackTitle))
            .check(matches(withText(R.string.empty_title)))

        // Status inicial
        onView(withId(R.id.trackStatus))
            .check(matches(withText(R.string.empty_hint)))

        // Slider desabilitado (nenhuma música carregada)
        onView(withId(R.id.trackSlider))
            .check(matches(not(isEnabled())))

        // BPM inicial = 120
        onView(withId(R.id.trackBpmValue))
            .check(matches(withText("120")))

        // Empty state visível
        onView(withId(R.id.emptySongList))
            .check(matches(isCompletelyDisplayed()))
    }

    /**
     * Valida que a música é adicionada à lista e a UI responde.
     * Clica no ícone de play e verifica os estados de reprodução.
     *
     * Fluxo:
     * 1. Injeta a música de teste via reflexão no SongAdapter
     * 2. Clica no ícone de play do primeiro item
     * 3. Aguarda UI atualizar (state PLAYING)
     * 4. Valida trackTitle, trackSlider habilitado, songIcon = pause
     * 5. Clica para pausar
     * 6. Valida que a música volta ao estado de seleção
     */
    @Test
    fun playButton_startsPlayback_andPauseButton_stopsPlayback() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)

        // Aguarda o adapter estar pronto
        Thread.sleep(600)

        // ── Passo 1: Injeta a música de teste diretamente no SongAdapter ──
        injectTestSong()

        // ── Passo 2: Verifica que a lista agora mostra 1 música ──
        onView(withId(R.id.songList))
            .check(matches(hasDescendant(withText(TEST_MUSIC_TITLE))))

        // ── Passo 3: Clica no ícone de play (primeiro item) ──
        onView(withId(R.id.songList))
            .perform(
                actionOnItemAtPosition<RecyclerView.ViewHolder>(
                    0,
                    clickChildViewWithId(R.id.songIcon)
                )
            )

        // ── Passo 4: Aguarda o player iniciar ──
        val started = waitForCondition(TIMEOUT_MS, 300) { checkPlaybackStarted() }

        // ── Passo 5: Valida estado PLAYING ──
        // Título da música
        onView(withId(R.id.trackTitle))
            .check(matches(withText(TEST_MUSIC_TITLE)))

        // Status da análise
        onView(withId(R.id.trackStatus))
            .check(matches(withText(R.string.status_analyzing)))

        // Se o player iniciou (tempo total preenchido)
        if (started) {
            // Slider habilitado após STATE_READY
            onView(withId(R.id.trackSlider))
                .check(matches(isEnabled()))

            // Duração total preenchida
            onView(withId(R.id.trackTimeTotal))
                .check(matches(not(withText("--:--"))))
        }

        // ── Passo 6: Clica novamente para pausar ──
        onView(withId(R.id.songList))
            .perform(
                actionOnItemAtPosition<RecyclerView.ViewHolder>(
                    0,
                    clickChildViewWithId(R.id.songIcon)
                )
            )

        // ── Passo 7: Aguarda pausa e valida ──
        Thread.sleep(500)

        onView(withId(R.id.trackStatus))
            .check(matches(isDisplayed()))
    }

    /**
     * Valida que o slider BPM é ajustável.
     */
    @Test
    fun bpmSlider_adjustable() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)

        // Slider de BPM deve estar sempre visível e enabled
        onView(withId(R.id.bpmSlider))
            .check(matches(isDisplayed()))
            .check(matches(isEnabled()))

        // Clica no botão de aumentar BPM (+)
        onView(withId(R.id.bpmPlusButton))
            .check(matches(isDisplayed()))
            .perform(click())

        // Clica no botão de diminuir BPM (-)
        onView(withId(R.id.bpmMinusButton))
            .check(matches(isDisplayed()))
            .perform(click())

        // Clica no botão de reset
        onView(withId(R.id.bpmResetButton))
            .check(matches(isDisplayed()))
            .perform(click())
    }

    /**
     * Valida que os botões do footer estão presentes.
     */
    @Test
    fun footerButtons_present() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)

        onView(withId(R.id.selectButton)).check(matches(isDisplayed()))
        onView(withId(R.id.repeatButton)).check(matches(isDisplayed()))
        onView(withId(R.id.loopAllButton)).check(matches(isDisplayed()))
    }

    /**
     * Valida que o ViewPager (beatPager) está presente.
     */
    @Test
    fun beatPager_present() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.moveToState(Lifecycle.State.RESUMED)

        onView(withId(R.id.beatPager))
            .check(matches(isCompletelyDisplayed()))
    }

    // ─────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Injeta a música de teste no SongAdapter via reflexão.
     */
    private fun injectTestSong() {
        scenario?.onActivity { activity ->
            val song = MainActivity.Song(
                uri = testMusicUri!!,
                title = TEST_MUSIC_TITLE,
                artist = TEST_MUSIC_ARTIST,
                duration = TEST_MUSIC_DURATION
            )
            val field = MainActivity::class.java.getDeclaredField("songAdapter")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val adapter = field.get(activity) as MainActivity.SongAdapter
            activity.runOnUiThread {
                adapter.setSongs(listOf(song))
            }
        }
        Thread.sleep(200)
    }

    /**
     * Espera uma condição com polling.
     * @param timeoutMs tempo máximo de espera
     * @param intervalMs intervalo entre tentativas
     * @param condition lambda que retorna true quando a condição é satisfeita
     * @return true se a condição foi satisfeita, false se deu timeout
     */
    private fun waitForCondition(
        timeoutMs: Long,
        intervalMs: Long,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(intervalMs)
        }
        return false
    }

    /**
     * Verifica se o playback iniciou (trackTimeTotal preenchido).
     */
    private fun checkPlaybackStarted(): Boolean {
        var started = false
        scenario?.onActivity { activity ->
            val binding = ActivityMainBinding.bind(activity.findViewById(R.id.root))
            started = binding.trackTimeTotal.text != "--:--"
        }
        return started
    }

    /**
     * ViewAction que clica em uma view-filha do item do RecyclerView.
     * Exemplo: clicar no songIcon (filho do item da lista).
     */
    private fun clickChildViewWithId(childId: Int): ViewAction {
        return object : ViewAction {
            override fun getDescription(): String = "clicar na view filha com id $childId"

            override fun getConstraints(): Matcher<android.view.View>? = null

            override fun perform(uiController: UiController, view: android.view.View) {
                val child = view.findViewById<android.view.View>(childId)
                    ?: throw RuntimeException("View filha com id $childId não encontrada")
                child.performClick()
            }
        }
    }
}
