package com.cyberbot.mobile.ui.matrix

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import com.cyberbot.mobile.ui.theme.MatrixGreen

/**
 * A chuva de caracteres como pano de fundo de uma tela.
 *
 * Diferente da tela de bloqueio, aqui nao ha cartao nem contagem: a chuva so cai.
 * O desenho le o tempo de um State, entao redesenha a cada quadro sem recompor o
 * conteudo que estiver por cima. A opacidade e aplicada na camada inteira.
 */
@Composable
fun MatrixFundo(
    modifier: Modifier = Modifier,
    opacidade: Float = 0.5f,
    cor: Color = MatrixGreen,
) {
    val motor = remember { MotorMatrix() }
    val quadro = lembrarRelogioDaChuva()

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = opacidade },
    ) {
        motor.desenhar(
            escopo = this,
            tempoMs = quadro.value,
            cartao = Rect.Zero,
            progressoSaida = 0f,
            corPrincipal = cor,
            contagem = null,
            contaCheia = false,
        )
    }
}
