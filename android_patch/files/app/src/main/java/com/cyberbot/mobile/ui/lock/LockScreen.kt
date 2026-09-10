package com.cyberbot.mobile.ui.lock

import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cyberbot.mobile.core.security.AutenticacaoBiometrica
import com.cyberbot.mobile.ui.theme.Bg
import com.cyberbot.mobile.ui.theme.Danger
import com.cyberbot.mobile.ui.theme.Neon
import com.cyberbot.mobile.ui.theme.Orbitron
import com.cyberbot.mobile.ui.theme.Rajdhani
import com.cyberbot.mobile.ui.theme.TextDim

private val FUNDO = Color(0xFF03030A)

@Composable
fun LockRoute(
    onDesbloqueado: () -> Unit,
    onSumiu: () -> Unit,
    viewModel: LockViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val segundos by viewModel.segundos.collectAsStateWithLifecycle()
    val contexto = LocalContext.current
    val atividade = contexto as? FragmentActivity
    val autenticador = remember(atividade) { atividade?.let { AutenticacaoBiometrica(it) } }

    LaunchedEffect(autenticador) {
        viewModel.registrarProtecao(autenticador?.temProtecao() == true)
    }

    LockScreen(
        estado = estado,
        segundos = segundos,
        onTocar = {
            if (autenticador != null && viewModel.podeAutenticar()) {
                viewModel.marcarAutenticando()
                autenticador.pedir { resultado -> viewModel.tratarResultado(resultado) }
            }
        },
        onAbrirConfiguracoes = {
            runCatching {
                contexto.startActivity(
                    Intent(Settings.ACTION_SECURITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        },
        onDesbloqueado = onDesbloqueado,
        onSumiu = onSumiu,
    )
}

@Composable
private fun LockScreen(
    estado: EstadoLock,
    segundos: Int,
    onTocar: () -> Unit,
    onAbrirConfiguracoes: () -> Unit,
    onDesbloqueado: () -> Unit,
    onSumiu: () -> Unit,
) {
    val motor = remember { MotorMatrix() }
    val quadro = remember { mutableStateOf(0L) }
    val caixaCartao = remember { mutableStateOf(Rect.Zero) }
    val saida = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { quadro.value = it / 1_000_000L }
        }
    }

    val ehErro = estado == EstadoLock.CONTAGEM || estado == EstadoLock.BLOQUEIO
    val corBorda by animateColorAsState(
        targetValue = if (ehErro) Danger else Neon,
        animationSpec = tween(durationMillis = if (ehErro) 260 else 520),
        label = "corBordaCartao",
    )
    val pulso = rememberInfiniteTransition(label = "pulsoCartao")
    val brilho by pulso.animateFloat(
        initialValue = 0.30f,
        targetValue = 0.75f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (ehErro) 700 else 1900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "brilhoCartao",
    )

    LaunchedEffect(estado) {
        if (estado == EstadoLock.LIBERANDO) {
            // solta a Home imediatamente: ela se forma enquanto a chuva ainda some
            onDesbloqueado()
            saida.animateTo(1f, tween(durationMillis = 950, easing = FastOutSlowInEasing))
            onSumiu()
        }
    }

    val podeTocar = estado == EstadoLock.AGUARDANDO ||
        estado == EstadoLock.CONTAGEM ||
        estado == EstadoLock.AUTENTICANDO

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FUNDO)
            .pointerInput(estado) {
                detectTapGestures {
                    if (podeTocar && estado != EstadoLock.AUTENTICANDO) {
                        onTocar()
                    }
                }
            },
    ) {
        CartaoAcesso(
            estado = estado,
            corBorda = corBorda,
            brilho = brilho,
            onAbrirConfiguracoes = onAbrirConfiguracoes,
            modifier = Modifier
                .align(Alignment.Center)
                .onGloballyPositioned { caixaCartao.value = it.boundsInParent() },
        )

        Canvas(modifier = Modifier.fillMaxSize()) {
            motor.desenhar(
                escopo = this,
                tempoMs = quadro.value,
                cartao = caixaCartao.value,
                progressoSaida = saida.value,
                corPrincipal = Neon,
                contagem = if (estado == EstadoLock.CONTAGEM) segundos else null,
                contaCheia = ehErro,
            )
        }
    }
}

@Composable
private fun CartaoAcesso(
    estado: EstadoLock,
    corBorda: Color,
    brilho: Float,
    onAbrirConfiguracoes: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val forma = RoundedCornerShape(18.dp)
    val titulo = when (estado) {
        EstadoLock.AGUARDANDO -> "RESTRICTED ACCESS"
        EstadoLock.AUTENTICANDO -> "VERIFYING"
        EstadoLock.CONTAGEM -> "ACCESS DENIED"
        EstadoLock.BLOQUEIO -> "USER BLOCKED"
        EstadoLock.LIBERANDO -> "ACCESS GRANTED"
        EstadoLock.SEM_PROTECAO -> "NO DEVICE LOCK"
    }
    val subtitulo = when (estado) {
        EstadoLock.AGUARDANDO -> "BIOMETRIC OR DEVICE PIN REQUIRED"
        EstadoLock.AUTENTICANDO -> "WAITING FOR SYSTEM PROMPT"
        EstadoLock.CONTAGEM -> "AUTHENTICATION FAILED"
        EstadoLock.BLOQUEIO -> "ACCESS SUSPENDED"
        EstadoLock.LIBERANDO -> "WELCOME BACK"
        EstadoLock.SEM_PROTECAO -> "SET A SCREEN LOCK IN ANDROID"
    }
    val rodape = when (estado) {
        EstadoLock.AGUARDANDO -> "TAP TO AUTHENTICATE"
        EstadoLock.AUTENTICANDO -> "..."
        EstadoLock.CONTAGEM -> "TAP TO TRY AGAIN"
        EstadoLock.BLOQUEIO -> "TRY AGAIN LATER"
        EstadoLock.LIBERANDO -> ""
        EstadoLock.SEM_PROTECAO -> "TAP TO OPEN SECURITY SETTINGS"
    }

    Box(
        modifier = modifier
            .width(300.dp)
            .graphicsLayer { alpha = if (estado == EstadoLock.LIBERANDO) 1f else 1f }
            .background(Color(0xE6050512), forma)
            .border(1.5.dp, corBorda.copy(alpha = brilho), forma)
            .padding(horizontal = 22.dp, vertical = 28.dp)
            .let { if (estado == EstadoLock.SEM_PROTECAO) it else it },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = titulo,
                fontFamily = Orbitron,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                letterSpacing = 2.sp,
                color = corBorda,
                textAlign = TextAlign.Center,
            )
            Box(
                modifier = Modifier
                    .width(64.dp)
                    .height(1.dp)
                    .background(corBorda.copy(alpha = 0.6f)),
            )
            Text(
                text = subtitulo,
                fontFamily = Rajdhani,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 1.6.sp,
                color = TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            if (rodape.isNotEmpty()) {
                Text(
                    text = rodape,
                    fontFamily = Rajdhani,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    letterSpacing = 1.2.sp,
                    color = corBorda.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
