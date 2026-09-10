package com.cyberbot.mobile.ui.matrix

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos

/** Quantos quadros por segundo a chuva desenha. */
const val FPS_CHUVA = 30

/**
 * Relogio da chuva: entrega o tempo em milissegundos no maximo [fps] vezes por segundo.
 *
 * O withFrameNanos continua acordando a cada quadro do display, mas o State so muda
 * quando o intervalo alvo passa — e e a mudanca do State que dispara o redesenho do
 * Canvas. Como a chuva anda em colunas de caracteres discretos, 30 quadros por segundo
 * nao muda o que se ve e corta pela metade o trabalho de desenho.
 */
@Composable
fun lembrarRelogioDaChuva(fps: Int = FPS_CHUVA): State<Long> {
    val tempo = remember { mutableStateOf(0L) }
    LaunchedEffect(fps) {
        val intervalo = (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)
        var ultimo = 0L
        while (true) {
            withFrameNanos { nanos ->
                val ms = nanos / 1_000_000L
                if (ms - ultimo >= intervalo) {
                    ultimo = ms
                    tempo.value = ms
                }
            }
        }
    }
    return tempo
}
