package ai.crossmeeting.app.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RecordingUiState(
    val isRecording: Boolean = false,
    val elapsedSeconds: Int = 0,
    val finalTranscript: String = "",
    val interimText: String = "",
    val error: String? = null,
    /**
     * Queda de rede sendo recuperada (ex.: wifi → 4G). A gravação continua e o
     * áudio é bufferizado; isto existe só para a tela avisar em vez de parecer
     * travada. Não é erro — erro real usa [error] e encerra a sessão.
     */
    val reconnecting: Boolean = false,
    /** Sem nenhuma palavra transcrita há um tempo — encerramento se aproxima. */
    val silenceWarning: Boolean = false,
    /** Perto do teto de duração da reunião. */
    val durationWarning: Boolean = false,
    val saving: Boolean = false,
    val savedMeetingId: Long? = null,
    /** true só depois que o [RecordingService] terminou o desligamento gracioso (parou o
     *  mic, mandou "CloseStream" pro Deepgram e esperou a resposta final chegar) — a tela
     *  de gravação espera esse sinal antes de ler a transcrição pra salvar. */
    val stoppedAndFlushed: Boolean = false,
)

/**
 * Estado compartilhado entre [RecordingService] (que escreve) e [RecordingScreen] (que observa).
 * Singleton simples — só existe uma gravação por vez no app, igual ao desktop.
 */
object RecordingState {
    private val _state = MutableStateFlow(RecordingUiState())
    val state: StateFlow<RecordingUiState> = _state.asStateFlow()

    /** Atualização atômica (compare-and-set via [MutableStateFlow.update]). */
    fun update(transform: (RecordingUiState) -> RecordingUiState) {
        _state.update(transform)
    }

    /**
     * Instante da última palavra transcrita. Fica aqui, e não no serviço, porque
     * a tela também precisa mexer: o botão "continuar gravando" do aviso de
     * silêncio adia o encerramento.
     *
     * `@Volatile` porque é escrito pela thread do WebSocket e lido pelo laço do
     * timer, em outra coroutine.
     */
    @Volatile
    var lastSpeechAt: Long = System.currentTimeMillis()
        private set

    /** Marca fala agora e retira o aviso de silêncio, se estiver na tela. */
    fun markSpeech() {
        lastSpeechAt = System.currentTimeMillis()
        _state.update { if (it.silenceWarning) it.copy(silenceWarning = false) else it }
    }

    fun reset() {
        _state.value = RecordingUiState()
        lastSpeechAt = System.currentTimeMillis()
        AudioLevelState.reset()
    }
}

/**
 * Nível de áudio do microfone (0..1), separado do [RecordingState] de propósito — mesma
 * separação que o desktop usa (`useAudioCapture` mantém os `bars` do visualizador num
 * `useState` próprio, nunca junto do texto da transcrição). A thread do microfone escreve
 * aqui várias vezes por segundo; manter isso fora do estado da transcrição evita qualquer
 * chance de uma atualização de amplitude pisar numa atualização de transcrição que chegou
 * quase ao mesmo tempo pela thread do WebSocket.
 */
object AudioLevelState {
    private val _amplitude = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    fun set(value: Float) {
        _amplitude.value = value
    }

    fun reset() {
        _amplitude.value = 0f
    }
}
