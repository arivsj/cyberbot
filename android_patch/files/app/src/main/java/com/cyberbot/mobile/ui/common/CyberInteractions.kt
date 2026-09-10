package com.cyberbot.mobile.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.cyberbot.mobile.ui.theme.Bg
import com.cyberbot.mobile.ui.theme.Border
import com.cyberbot.mobile.ui.theme.Neon
import com.cyberbot.mobile.ui.theme.Surface
import com.cyberbot.mobile.ui.theme.Surface2
import com.cyberbot.mobile.ui.theme.TextDim

private const val ESCALA_BOTAO = 0.95f
private const val ESCALA_BOTAO_TEXTO = 0.93f
private const val ESCALA_ICONE = 0.86f
private const val ESCALA_CARTAO = 0.97f

/**
 * Encolhe suavemente o elemento enquanto o dedo esta pressionando e devolve com mola.
 * A leitura do estado acontece dentro do graphicsLayer, entao a animacao redesenha
 * a camada sem recompor o conteudo. Sem vibracao do aparelho.
 */
@Composable
fun Modifier.cyberPress(
    interactionSource: InteractionSource,
    enabled: Boolean = true,
    escala: Float = ESCALA_BOTAO,
): Modifier {
    val pressionado by interactionSource.collectIsPressedAsState()
    val atual by animateFloatAsState(
        targetValue = if (pressionado && enabled) escala else 1f,
        animationSpec = spring(
            dampingRatio = 0.42f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "cyberPressEscala",
    )
    return this.graphicsLayer {
        scaleX = atual
        scaleY = atual
    }
}

/** Botao principal: contorno neon, fundo translucido e resposta ao toque. */
@Composable
fun CyberButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    carregando: Boolean = false,
    content: @Composable RowScope.() -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }
    val ativo = enabled && !carregando
    Button(
        onClick = onClick,
        modifier = modifier.cyberPress(interacao, ativo, ESCALA_BOTAO),
        enabled = ativo,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.buttonColors(
            containerColor = Neon.copy(alpha = 0.16f),
            contentColor = Neon,
            disabledContainerColor = Surface2,
            disabledContentColor = TextDim,
        ),
        border = BorderStroke(1.dp, if (ativo) Neon.copy(alpha = 0.55f) else Border),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        interactionSource = interacao,
    ) {
        if (carregando) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(10.dp))
        }
        content()
    }
}

/** Acao secundaria em texto, para cabecalhos de tela. */
@Composable
fun CyberTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        modifier = modifier.cyberPress(interacao, enabled, ESCALA_BOTAO_TEXTO),
        enabled = enabled,
        shape = MaterialTheme.shapes.extraSmall,
        colors = ButtonDefaults.textButtonColors(
            contentColor = Neon,
            disabledContentColor = TextDim,
        ),
        interactionSource = interacao,
        content = content,
    )
}

/** Acao terciaria com contorno, para testes e conexoes. */
@Composable
fun CyberOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.cyberPress(interacao, enabled, ESCALA_BOTAO),
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Neon,
            disabledContentColor = TextDim,
        ),
        border = BorderStroke(1.dp, if (enabled) Neon.copy(alpha = 0.5f) else Border),
        interactionSource = interacao,
        content = content,
    )
}

/** Botao de icone (anexos, acoes rapidas). */
@Composable
fun CyberIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }
    IconButton(
        onClick = onClick,
        modifier = modifier.cyberPress(interacao, enabled, ESCALA_ICONE),
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = Neon,
            disabledContentColor = TextDim,
        ),
        interactionSource = interacao,
        content = content,
    )
}

/** Botao flutuante de acao: neon solido no lugar do azul Material. */
@Composable
fun CyberFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = modifier.cyberPress(interacao, enabled, 0.9f),
        shape = MaterialTheme.shapes.small,
        containerColor = if (enabled) Neon else Surface2,
        contentColor = if (enabled) Bg else TextDim,
        interactionSource = interacao,
        content = content,
    )
}

/**
 * Cartao clicavel. No toque: encolhe, a borda acende em neon e a onda de toque some
 * por baixo do conteudo, sem borrar o texto.
 */
@Composable
fun PressableCyberCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(8.dp),
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    contentModifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interacao = remember { MutableInteractionSource() }
    val pressionado by interacao.collectIsPressedAsState()
    val borda by animateColorAsState(
        targetValue = if (pressionado && enabled) Neon.copy(alpha = 0.9f) else Border,
        animationSpec = tween(durationMillis = 140),
        label = "cyberCartaoBorda",
    )
    val fundo by animateColorAsState(
        targetValue = if (pressionado && enabled) Surface2 else Surface,
        animationSpec = tween(durationMillis = 140),
        label = "cyberCartaoFundo",
    )

    Card(
        modifier = modifier
            .cyberPress(interacao, enabled, ESCALA_CARTAO)
            .clip(MaterialTheme.shapes.medium)
            .clickable(
                interactionSource = interacao,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
        colors = CardDefaults.cardColors(containerColor = fundo),
        border = BorderStroke(1.dp, borda),
    ) {
        Column(
            modifier = contentModifier.padding(contentPadding),
            verticalArrangement = verticalArrangement,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}
