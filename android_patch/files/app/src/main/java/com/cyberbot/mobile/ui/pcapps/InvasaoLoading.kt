package com.cyberbot.mobile.ui.pcapps

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyberbot.mobile.ui.theme.Border
import com.cyberbot.mobile.ui.theme.Danger
import com.cyberbot.mobile.ui.theme.JetBrainsMono
import com.cyberbot.mobile.ui.theme.MatrixGreen
import com.cyberbot.mobile.ui.theme.Neon
import com.cyberbot.mobile.ui.theme.Orbitron
import com.cyberbot.mobile.ui.theme.Surface
import com.cyberbot.mobile.ui.theme.TextDim
import kotlinx.coroutines.delay
import kotlin.random.Random

/** Etapas mostradas enquanto a lista de apps do PC esta sendo buscada. */
private val ETAPAS = listOf(
    "resolvendo host",
    "varrendo portas",
    "contornando firewall",
    "lendo processos",
    "catalogando apps",
    "acesso concedido",
)

private const val PASSO_MS = 60L
private const val GLIFOS_HEX = "0123456789ABCDEF"
private const val GLITCH = "!@#\$%&*<>/|=+?"
private val CURSOR = "\u2588"

/**
 * Loading de "invasao" enquanto a lista de apps do PC e atualizada.
 *
 * A animacao anda em passos de 60ms em vez de quadro a quadro: da conta do
 * embaralhado dos hexadecimais e do cursor, sem recompor muito mais vezes do que
 * o olho percebe.
 */
@Composable
fun InvasaoLoading(
    modifier: Modifier = Modifier,
    duracaoMs: Int = 1600,
    titulo: String = "INVASAO EM CURSO",
) {
    var decorrido by remember { mutableStateOf(0f) }

    LaunchedEffect(duracaoMs) {
        while (decorrido < duracaoMs) {
            delay(PASSO_MS)
            decorrido = (decorrido + PASSO_MS).coerceAtMost(duracaoMs.toFloat())
        }
    }

    val progresso = (decorrido / duracaoMs.toFloat()).coerceIn(0f, 1f)
    val passo = (decorrido / PASSO_MS).toInt()
    val etapaAtual = (progresso * ETAPAS.size).toInt().coerceIn(0, ETAPAS.size - 1)
    val porcento = (progresso * 100).toInt()
    val cursor = if (passo % 2 == 0) CURSOR else " "

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface.copy(alpha = 0.92f), RoundedCornerShape(14.dp))
            .border(1.dp, Neon.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tituloComGlitch(titulo, passo, progresso),
                fontFamily = Orbitron,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                letterSpacing = 2.sp,
                color = Neon,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$porcento%",
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                color = MatrixGreen,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .background(Border.copy(alpha = 0.45f), RoundedCornerShape(4.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .graphicsLayer {
                        scaleX = progresso
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .background(
                        if (progresso >= 1f) MatrixGreen else Neon,
                        RoundedCornerShape(4.dp),
                    ),
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ETAPAS.forEachIndexed { indice, etapa ->
                val feita = indice < etapaAtual || progresso >= 1f
                val atual = indice == etapaAtual && progresso < 1f
                if (indice > etapaAtual + 1 && progresso < 1f) return@forEachIndexed
                val marca = when {
                    feita -> "[ ok ]"
                    atual -> "[ .. ]"
                    else -> "[    ]"
                }
                val cor = when {
                    feita -> MatrixGreen
                    atual -> Neon
                    else -> TextDim
                }
                Text(
                    text = marca + " " + etapa + if (atual) cursor else "",
                    fontFamily = JetBrainsMono,
                    fontSize = 11.sp,
                    color = cor,
                )
            }
        }

        Spacer(Modifier.height(2.dp))
        Text(
            text = linhaHexadecimal(passo, progresso),
            fontFamily = JetBrainsMono,
            fontSize = 10.sp,
            color = if (progresso >= 1f) MatrixGreen else Danger.copy(alpha = 0.75f),
        )
    }
}

private fun tituloComGlitch(titulo: String, passo: Int, progresso: Float): String {
    if (progresso >= 1f) return titulo
    if (passo % 7 != 0) return titulo
    val posicao = Random(passo).nextInt(titulo.length)
    val troca = GLITCH[Random(passo * 31).nextInt(GLITCH.length)]
    return titulo.substring(0, posicao) + troca + titulo.substring(posicao + 1)
}

/** Linha de hexadecimal embaralhada, como um dump de memoria acontecendo. */
private fun linhaHexadecimal(passo: Int, progresso: Float): String {
    if (progresso >= 1f) return "> canal estabelecido. lista recebida."
    val sorteio = Random(passo)
    val grupos = (0 until 8).joinToString(" ") {
        (0 until 4).joinToString("") { GLIFOS_HEX[sorteio.nextInt(GLIFOS_HEX.length)].toString() }
    }
    return "> " + grupos
}
