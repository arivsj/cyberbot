package com.cyberbot.mobile.ui.lock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cyberbot.mobile.core.security.ResultadoAuth
import com.cyberbot.mobile.core.storage.SettingsStorage
import com.cyberbot.mobile.data.repo.SecurityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Em que ponto do fluxo de entrada o app esta. */
enum class EstadoLock {
    /** Chuva normal, esperando o usuario tocar para se autenticar. */
    AGUARDANDO,

    /** Prompt de biometria ou senha do aparelho aberto. */
    AUTENTICANDO,

    /** Errou: cartao vermelho e contagem regressiva de 30s no topo. */
    CONTAGEM,

    /** Passou dos 30s sem entrar: bloqueado por 1 hora. */
    BLOQUEIO,

    /** Entrou: a chuva some do centro para as pontas e a Home se forma. */
    LIBERANDO,

    /** O aparelho nao tem biometria nem bloqueio de tela configurado. */
    SEM_PROTECAO,
}

private const val SEGUNDOS_CONTAGEM = 30
private const val DURACAO_BLOQUEIO_MS = 60L * 60L * 1000L

@HiltViewModel
class LockViewModel @Inject constructor(
    private val settingsStorage: SettingsStorage,
    private val securityRepository: SecurityRepository,
) : ViewModel() {

    private val _estado = MutableStateFlow(EstadoLock.AGUARDANDO)
    val estado: StateFlow<EstadoLock> = _estado.asStateFlow()

    private val _segundos = MutableStateFlow(SEGUNDOS_CONTAGEM)
    val segundos: StateFlow<Int> = _segundos.asStateFlow()

    private val _temProtecao = MutableStateFlow(true)
    val temProtecao: StateFlow<Boolean> = _temProtecao.asStateFlow()

    /**
     * Fica no ViewModel, e nao em rememberSaveable: assim a rotacao nao pede a
     * autenticacao de novo, mas a morte do processo sim (nao ha estado salvo em disco).
     */
    private val _liberado = MutableStateFlow(false)
    val liberado: StateFlow<Boolean> = _liberado.asStateFlow()

    private val _overlay = MutableStateFlow(true)
    val overlay: StateFlow<Boolean> = _overlay.asStateFlow()

    private var temporizador: Job? = null
    private var tentativas = 0

    init {
        viewModelScope.launch {
            val ate = settingsStorage.bloqueioAte.first()
            if (ate > System.currentTimeMillis()) {
                _estado.value = EstadoLock.BLOQUEIO
                agendarFimDoBloqueio(ate)
            }
        }
    }

    fun registrarProtecao(tem: Boolean) {
        _temProtecao.value = tem
        if (!tem && _estado.value == EstadoLock.AGUARDANDO) {
            _estado.value = EstadoLock.SEM_PROTECAO
        }
    }

    /** Só abre o prompt se nao estiver cumprindo bloqueio nem ja autenticando. */
    fun podeAutenticar(): Boolean =
        _estado.value == EstadoLock.AGUARDANDO || _estado.value == EstadoLock.CONTAGEM

    fun marcarAutenticando() {
        if (podeAutenticar()) _estado.value = EstadoLock.AUTENTICANDO
    }

    fun tratarResultado(resultado: ResultadoAuth) {
        when (resultado) {
            is ResultadoAuth.Sucesso -> {
                temporizador?.cancel()
                tentativas = 0
                viewModelScope.launch { settingsStorage.limparBloqueio() }
                _estado.value = EstadoLock.LIBERANDO
                _liberado.value = true
            }

            is ResultadoAuth.Falha, is ResultadoAuth.Cancelado -> {
                tentativas += 1
                _estado.value = EstadoLock.CONTAGEM
                iniciarContagem()
            }

            is ResultadoAuth.Indisponivel -> {
                _estado.value = EstadoLock.SEM_PROTECAO
            }
        }
    }

    private fun iniciarContagem() {
        temporizador?.cancel()
        _segundos.value = SEGUNDOS_CONTAGEM
        temporizador = viewModelScope.launch {
            while (_segundos.value > 0) {
                delay(1000)
                _segundos.value -= 1
            }
            aplicarBloqueio()
        }
    }

    private suspend fun aplicarBloqueio() {
        val ate = System.currentTimeMillis() + DURACAO_BLOQUEIO_MS
        settingsStorage.registrarBloqueioAte(ate)
        _estado.value = EstadoLock.BLOQUEIO
        registrarIncidente(ate)
        agendarFimDoBloqueio(ate)
    }

    /**
     * O incidente vai para o PC. Se o celular estiver sem rede ou sem pareamento, o
     * bloqueio local continua valendo do mesmo jeito.
     */
    private suspend fun registrarIncidente(bloqueadoAte: Long) {
        val formato = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        runCatching {
            securityRepository.reportIncident(
                motivo = "Acesso bloqueado por $tentativas tentativa(s) sem autenticacao",
                tentativas = tentativas,
                bloqueadoAte = formato.format(Date(bloqueadoAte)),
            )
        }
    }

    private fun agendarFimDoBloqueio(ate: Long) {
        temporizador?.cancel()
        temporizador = viewModelScope.launch {
            val espera = (ate - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(espera)
            settingsStorage.limparBloqueio()
            tentativas = 0
            _segundos.value = SEGUNDOS_CONTAGEM
            _estado.value = if (_temProtecao.value) EstadoLock.AGUARDANDO else EstadoLock.SEM_PROTECAO
        }
    }

    /** A tela de bloqueio avisa que a saida comecou: a Home ja pode se formar. */
    fun confirmarEntrada() {
        _liberado.value = true
    }

    /** Chamado quando a chuva terminou de sumir: o overlay sai e so a Home fica. */
    fun finalizarSaida() {
        _overlay.value = false
        // Volta para AGUARDANDO: se ficar em LIBERANDO, o proximo relock cai no ramo
        // que desfaz o bloqueio e o app nunca volta a pedir autenticacao.
        _estado.value = EstadoLock.AGUARDANDO
    }

    /** Volta da tela de fundo: pede de novo, a nao ser que esteja no meio de um prompt. */
    fun relockar() {
        if (!_temProtecao.value) return
        _liberado.value = false
        _overlay.value = true
        viewModelScope.launch {
            val ate = settingsStorage.bloqueioAte.first()
            val agora = System.currentTimeMillis()
            if (ate > agora) {
                _estado.value = EstadoLock.BLOQUEIO
                agendarFimDoBloqueio(ate)
            } else {
                temporizador?.cancel()
                _segundos.value = SEGUNDOS_CONTAGEM
                _estado.value = EstadoLock.AGUARDANDO
            }
        }
    }

    override fun onCleared() {
        temporizador?.cancel()
        super.onCleared()
    }
}
