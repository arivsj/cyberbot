package com.cyberbot.mobile.ui.lock

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.graphics.Color
import kotlin.math.max


private const val LARGURA_DIGITO = 4
private const val ALTURA_DIGITO = 7
private const val DURACAO_FORMACAO_MS = 900f
private const val GLIFOS_CONTAGEM = "0123456789ABCDEF<>/|*+=#"

private val DIGITOS = arrayOf(
    arrayOf("1111", "1001", "1001", "1001", "1001", "1001", "1111"),
    arrayOf("0010", "0110", "0010", "0010", "0010", "0010", "0111"),
    arrayOf("1111", "0001", "0001", "1111", "1000", "1000", "1111"),
    arrayOf("1111", "0001", "0001", "1111", "0001", "0001", "1111"),
    arrayOf("1001", "1001", "1001", "1111", "0001", "0001", "0001"),
    arrayOf("1111", "1000", "1000", "1111", "0001", "0001", "1111"),
    arrayOf("1111", "1000", "1000", "1111", "1001", "1001", "1111"),
    arrayOf("1111", "0001", "0001", "0010", "0010", "0100", "0100"),
    arrayOf("1111", "1001", "1001", "1111", "1001", "1001", "1111"),
    arrayOf("1111", "1001", "1001", "1111", "0001", "0001", "1111"),
)

/**
 * Contagem regressiva desenhada com os mesmos glifos da chuva: os caracteres caem no
 * lugar e vao formando os digitos no topo da tela, de cima para baixo e da esquerda
 * para a direita. A cada segundo a formacao recomeca com o numero novo.
 */
internal class ContagemGlifos {
    private val pincel = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private var ultimoValor = Int.MIN_VALUE
    private var tempoTroca = 0L

    fun desenhar(
        tela: Canvas,
        valor: Int,
        larguraTela: Float,
        celulaChuva: Float,
        cor: Color,
        tempoMs: Long,
    ) {
        if (valor != ultimoValor) {
            ultimoValor = valor
            tempoTroca = tempoMs
        }
        val texto = valor.coerceAtLeast(0).toString().padStart(2, '0')
        val escala = celulaChuva * 1.5f
        pincel.textSize = escala * 0.92f

        val colunasPorDigito = LARGURA_DIGITO + 1
        val colunasTotal = texto.length * colunasPorDigito - 1
        val larguraTotal = colunasTotal * escala
        val x0 = (larguraTela - larguraTotal) / 2f
        val y0 = celulaChuva * 3.4f

        val decorrido = (tempoMs - tempoTroca).toFloat()
        val total = texto.length * LARGURA_DIGITO * ALTURA_DIGITO
        val formadas = max(1f, decorrido / DURACAO_FORMACAO_MS * total)

        var indice = 0
        texto.forEachIndexed { posDigito, caractere ->
            val digito = caractere.digitToIntOrNull() ?: 0
            val padrao = DIGITOS[digito]
            for (linha in 0 until ALTURA_DIGITO) {
                for (coluna in 0 until LARGURA_DIGITO) {
                    if (padrao[linha][coluna] != '1') {
                        indice++
                        continue
                    }
                    val restante = formadas - indice
                    indice++
                    if (restante <= 0f) continue
                    val chegada = restante.coerceAtMost(1f)
                    val x = x0 + (posDigito * colunasPorDigito + coluna) * escala + escala / 2f
                    val y = y0 + (linha + 0.5f) * escala - (1f - chegada) * escala * 4f
                    val alpha = chegada
                    val fatia = (tempoMs / 70L + indice * 37L)
                    val glifo = GLIFOS_CONTAGEM[((fatia % GLIFOS_CONTAGEM.length).toInt() +
                        GLIFOS_CONTAGEM.length) % GLIFOS_CONTAGEM.length]
                    // cai como glifo aleatorio e, ao assentar, vira o proprio digito:
                    // assim a contagem continua legivel mesmo sendo feita de caracteres.
                    val simbolo = if (chegada > 0.9f) caractere else glifo
                    pincel.color = android.graphics.Color.argb(
                        (alpha * 255).toInt().coerceIn(0, 255),
                        (cor.red * 255).toInt(),
                        (cor.green * 255).toInt(),
                        (cor.blue * 255).toInt(),
                    )
                    tela.drawText(simbolo.toString(), x, y, pincel)
                }
            }
        }
    }

    fun reiniciar() {
        ultimoValor = Int.MIN_VALUE
    }
}
