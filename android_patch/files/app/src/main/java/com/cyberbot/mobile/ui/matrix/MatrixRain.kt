package com.cyberbot.mobile.ui.matrix

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import com.cyberbot.mobile.ui.theme.Danger
import com.cyberbot.mobile.ui.theme.MatrixGreen
import com.cyberbot.mobile.ui.theme.Neon
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random

/** Katakana + digitos + simbolos, como na chuva do filme. */
private const val GLIFOS =
    "アァカサタナハマヤラワイキシチニヒミリウクスツヌフムユルエケセテネヘメレオコソトノホモヨロ" +
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ<>/|*+=#$%&@"

private const val TAMANHO_RASTRO = 16
private val INTERVALO_TROCA_GLIFO_MS = 90L
private val DURACAO_PALAVRA_MS = 2200L
private val DURACAO_FADE_PALAVRA_MS = 420L
private const val MAX_PALAVRAS = 3

/** Palavras que se formam onde a chuva esbarra no cartao. */
private val PALAVRAS_AVISO = listOf("GO AWAY", "DANGER", "GET OUT", "STAY BACK", "DENIED", "TURN BACK")

private fun semAcento(texto: String) = texto

internal class PalavraImpacto(
    val texto: String,
) {
    var idadeMs = 0L
    fun alpha(): Float = when {
        idadeMs < DURACAO_FADE_PALAVRA_MS ->
            idadeMs.toFloat() / DURACAO_FADE_PALAVRA_MS
        idadeMs > DURACAO_PALAVRA_MS ->
            max(0f, 1f - (idadeMs - DURACAO_PALAVRA_MS).toFloat() / DURACAO_FADE_PALAVRA_MS)
        else -> 1f
    }
    fun terminou(): Boolean = idadeMs > DURACAO_PALAVRA_MS + DURACAO_FADE_PALAVRA_MS
}

internal class ColunaChuva(
    val indice: Int,
    var x: Float,
    var y: Float,
    var velocidade: Float,
    private val altura: Float,
    private val semente: Int,
) {
    private var idadeTotal = 0L
    var palavra: PalavraImpacto? = null
    var alphaSaida = 1f

    /** Posicao antes do ultimo passo, para detectar o cruzamento da borda do cartao. */
    var yAnterior = y
    private var posicaoInicial = true

    /** Glifo da celula: muda com o tempo, sem guardar buffer. */
    fun glifo(celula: Int, tempoMs: Long): Char {
        val fatia = tempoMs / INTERVALO_TROCA_GLIFO_MS
        val h = (indice * 73856093) xor (celula * 19349663) xor (fatia.toInt() * 83492791)
        val i = (h % GLIFOS.length + GLIFOS.length) % GLIFOS.length
        return GLIFOS[i]
    }

    fun atualizar(deltaMs: Float, alturaTotal: Float, cartao: Rect, podeSoletrar: Boolean) {
        palavra?.let { it.idadeMs += deltaMs.toLong() }
        if (palavra?.terminou() == true) {
            palavra = null
            // Volta a cair logo acima do cartao para bater nele de novo em poucos
            // segundos. Sem isso a coluna levaria quase um minuto para dar a volta.
            if (podeSoletrar) {
                y = cartao.top - 90f - Random(semente + idadeTotal).nextInt(60, 520)
                yAnterior = y
            }
        }
        if (palavra == null) {
            yAnterior = y
            y += velocidade * deltaMs / 1000f
            if (y > alturaTotal + 200f) {
                y = -Random(semente + (y.toInt())).nextInt(80, 700).toFloat()
                yAnterior = y
            }
        }
        if (posicaoInicial) {
            posicaoInicial = false
            yAnterior = y
        }
        idadeTotal += deltaMs.toLong()
    }
}

/**
 * Motor da chuva de caracteres. Guarda o estado das colunas fora do Compose para
 * nao recompor a cada quadro: o desenho le o tempo de um State e so redesenha.
 */
internal class MotorMatrix {
    private var colunas: Array<ColunaChuva> = emptyArray()
    private var larguraAtual = 0f
    private var alturaAtual = 0f
    private var celulaAtual = 0f
    private var ultimoTempo = 0L
    private val aleatorio = Random(20260910)
    private val contagemGlifos = ContagemGlifos()

    val pincel = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val pincelPalavra = Paint().apply {
        isAntiAlias = true
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        letterSpacing = 0f
    }

    fun configurar(largura: Float, altura: Float, celula: Float) {
        if (largura <= 0f || altura <= 0f || celula <= 0f) return
        if (abs(largura - larguraAtual) < 1f && abs(altura - alturaAtual) < 1f &&
            abs(celula - celulaAtual) < 1f
        ) {
            return
        }
        larguraAtual = largura
        alturaAtual = altura
        celulaAtual = celula
        pincel.textSize = celula * 0.86f
        pincelPalavra.textSize = celula * 0.82f

        val quantidade = max(1, (largura / celula).toInt())
        colunas = Array(quantidade) { i ->
            ColunaChuva(
                indice = i,
                x = (i + 0.5f) * celula,
                y = aleatorio.nextFloat() * altura,
                velocidade = celula * (2.2f + aleatorio.nextFloat() * 4.6f),
                altura = altura,
                semente = i * 7919,
            )
        }
    }

    private fun atualizar(tempoMs: Long, altura: Float, cartao: Rect) {
        if (ultimoTempo == 0L) {
            ultimoTempo = tempoMs
            return
        }
        val delta = (tempoMs - ultimoTempo).coerceIn(0L, 50L).toFloat()
        ultimoTempo = tempoMs
        val podeSoletrar = cartao.height > 0f
        colunas.forEach { it.atualizar(delta, altura, cartao, podeSoletrar) }
    }

    /**
     * Registra o impacto da chuva na borda de cima do cartao: a coluna para de cair e
     * passa a soletrar uma palavra de aviso, verticalmente, no ponto exato da batida.
     */
    private fun registrarImpactos(cartao: Rect) {
        if (cartao.width <= 0f) return
        val ativas = colunas.count { it.palavra != null }
        colunas.forEach { coluna ->
            if (coluna.palavra != null) return@forEach
            if (coluna.x < cartao.left || coluna.x > cartao.right) return@forEach
            // cruzou a borda de cima neste quadro
            if (coluna.yAnterior < cartao.top && coluna.y >= cartao.top) {
                if (ativas >= MAX_PALAVRAS) return@forEach
                val perto = colunas.any { outra ->
                    outra !== coluna && outra.palavra != null && abs(outra.x - coluna.x) < celulaAtual * 4f
                }
                if (perto) return@forEach
                coluna.palavra = PalavraImpacto(PALAVRAS_AVISO.random(aleatorio))
                coluna.y = cartao.top
            }
        }
    }

    /** 0 = chuva normal, 1 = totalmente apagada. Sai do centro para as pontas. */
    private fun aplicarSaida(progresso: Float, largura: Float) {
        if (progresso <= 0f) {
            colunas.forEach { it.alphaSaida = 1f }
            return
        }
        val centro = largura / 2f
        val maxDist = centro
        colunas.forEach { coluna ->
            val distancia = abs(coluna.x - centro) / maxDist
            val inicio = distancia * 0.55f
            val local = ((progresso - inicio) / 0.45f).coerceIn(0f, 1f)
            coluna.alphaSaida = 1f - local
        }
    }

    fun desenhar(
        escopo: DrawScope,
        tempoMs: Long,
        cartao: Rect,
        progressoSaida: Float,
        corPrincipal: Color,
        contagem: Int?,
        contaCheia: Boolean,
    ) {
        val largura = escopo.size.width
        val altura = escopo.size.height
        configurar(largura, altura, celulaAtual.takeIf { it > 0f } ?: (largura / 42f))
        atualizar(tempoMs, altura, cartao)
        registrarImpactos(cartao)
        aplicarSaida(progressoSaida, largura)

        val tela = escopo.drawContext.canvas.nativeCanvas
        val celula = celulaAtual
        if (celula <= 0f) return

        val corBase = if (contaCheia) Danger else corPrincipal
        if (contagem == null) contagemGlifos.reiniciar()

        colunas.forEach { coluna ->
            if (coluna.alphaSaida <= 0.01f) return@forEach
            val sobreCartao = cartao.contains(Offset(coluna.x, coluna.y))
            var i = 0
            while (i < TAMANHO_RASTRO) {
                val y = coluna.y - i * celula
                if (y < -celula || y > altura + celula) {
                    i++
                    continue
                }
                val dentroDoCartao = cartao.contains(Offset(coluna.x, y))
                val fade = (1f - i / TAMANHO_RASTRO.toFloat())
                // sobre o cartao a chuva fica discreta para nao sujar o texto
                val atenuacao = if (dentroDoCartao) 0.22f else 1f
                val alpha = (fade * fade * 0.95f + if (i == 0) 0.05f else 0f) *
                    coluna.alphaSaida * atenuacao
                if (alpha <= 0.02f) {
                    i++
                    continue
                }
                pincel.color = android.graphics.Color.argb(
                    (alpha * 255).toInt().coerceIn(0, 255),
                    (corBase.red * 255).toInt(),
                    (corBase.green * 255).toInt(),
                    (corBase.blue * 255).toInt(),
                )
                pincel.alpha = (alpha * 255).toInt().coerceIn(0, 255)
                val glifo = if (i == 0) {
                    coluna.glifo(0, tempoMs)
                } else {
                    coluna.glifo(i, tempoMs - i * 130L)
                }
                tela.drawText(glifo.toString(), coluna.x, y, pincel)
                i++
            }

            coluna.palavra?.let { palavra ->
                val alpha = palavra.alpha() * coluna.alphaSaida
                if (alpha > 0.02f) {
                    pincelPalavra.color = android.graphics.Color.argb(
                        (alpha * 255).toInt().coerceIn(0, 255),
                        (corBase.red * 255).toInt(),
                        (corBase.green * 255).toInt(),
                        (corBase.blue * 255).toInt(),
                    )
                    palavra.texto.forEachIndexed { indiceLetra, letra ->
                        val yLetra = cartao.top + celula * (indiceLetra + 1.2f)
                        if (letra != ' ') {
                            tela.drawText(letra.toString(), coluna.x, yLetra, pincelPalavra)
                        }
                    }
                }
            }
        }

        if (contagem != null) {
            contagemGlifos.desenhar(tela, contagem, largura, celula, corBase, tempoMs)
        }
    }
}
