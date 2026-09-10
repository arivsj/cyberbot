package com.cyberbot.mobile.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cyberbot.mobile.ui.theme.Neon
import com.cyberbot.mobile.ui.theme.Neon2
import com.cyberbot.mobile.ui.theme.Rajdhani
import com.cyberbot.mobile.ui.theme.Surface
import com.cyberbot.mobile.ui.theme.Surface2
import com.cyberbot.mobile.ui.theme.TextDim

/** Item do menu inferior: rota de destino, rótulo e ícone. */
data class CyberNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val ALTURA_BARRA = 62.dp
private val TAMANHO_ICONE = 22.dp

/**
 * Menu inferior cyberpunk animado.
 *
 * Substitui o BottomAppBar anterior (que ficava só com texto) por uma barra com
 * ícones, um "pill" de seleção que desliza entre os itens e brilho neon pulsante.
 * A navegação em si continua igual: quem chama só informa a rota atual e recebe
 * o clique.
 */
@Composable
fun CyberBottomBar(
    items: List<CyberNavItem>,
    selectedRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val indiceSelecionado = items.indexOfFirst { it.route == selectedRoute }
    val indiceAnimado = animateFloatAsState(
        targetValue = indiceSelecionado.coerceAtLeast(0).toFloat(),
        animationSpec = spring(
            dampingRatio = 0.55f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "cyberNavIndice",
    )
    val alphaSelecao = animateFloatAsState(
        targetValue = if (indiceSelecionado >= 0) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "cyberNavAlphaSelecao",
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Surface2, Surface)))
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Neon.copy(alpha = 0.55f),
                            Neon2.copy(alpha = 0.55f),
                            Color.Transparent,
                        ),
                    ),
                ),
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(ALTURA_BARRA),
        ) {
            val larguraItem = maxWidth / items.size

            Box(
                modifier = Modifier
                    .width(larguraItem)
                    .height(ALTURA_BARRA)
                    .offset { IntOffset((larguraItem * indiceAnimado.value).roundToPx(), 0) }
                    .alpha(alphaSelecao.value),
            ) {
                SelecaoNeon(larguraItem)
            }

            Row(modifier = Modifier.fillMaxSize()) {
                items.forEach { item ->
                    ItemBarra(
                        item = item,
                        selecionado = item.route == selectedRoute,
                        onClick = { onSelect(item.route) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SelecaoNeon(largura: androidx.compose.ui.unit.Dp) {
    val transicao = rememberInfiniteTransition(label = "cyberNavPulso")
    val pulso = transicao.animateFloat(
        initialValue = 0.16f,
        targetValue = 0.36f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "cyberNavPulsoAlpha",
    )

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .width(largura * 0.8f)
                .height(ALTURA_BARRA)
                // alpha lido dentro do graphicsLayer: o pulso é infinito, então assim ele
                // só redesenha a camada e não recompoẽ a barra inteira a cada frame.
                .graphicsLayer { alpha = pulso.value }
                .background(
                    Brush.radialGradient(
                        colors = listOf(Neon, Color.Transparent),
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .width(largura * 0.78f)
                .height(ALTURA_BARRA - 12.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Neon.copy(alpha = 0.18f),
                            Neon2.copy(alpha = 0.05f),
                        ),
                    ),
                )
                .border(1.dp, Neon.copy(alpha = 0.35f), RoundedCornerShape(18.dp)),
        )
    }
}

@Composable
private fun ItemBarra(
    item: CyberNavItem,
    selecionado: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interacao = remember { MutableInteractionSource() }
    val pressionado by interacao.collectIsPressedAsState()

    val escala by animateFloatAsState(
        targetValue = when {
            pressionado -> 0.85f
            selecionado -> 1.14f
            else -> 1f
        },
        animationSpec = spring(
            dampingRatio = 0.4f,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "cyberNavEscala",
    )
    val corIcone by animateColorAsState(
        targetValue = if (selecionado) Neon else TextDim,
        animationSpec = tween(durationMillis = 220),
        label = "cyberNavCorIcone",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = interacao,
                indication = null,
            ) {
                onClick()
            }
            // Compensa o espaço de descida que a caixa do Text reserva abaixo das letras:
            // sem isso o conjunto ícone+rótulo fica visualmente acima do centro da barra.
            .padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = item.icon,
            contentDescription = item.label,
            tint = corIcone,
            modifier = Modifier
                .size(TAMANHO_ICONE)
                .scale(escala),
        )
        Spacer(Modifier.height(5.dp))
        Text(
            text = item.label,
            color = corIcone,
            fontFamily = Rajdhani,
            fontSize = 11.sp,
            fontWeight = if (selecionado) FontWeight.Bold else FontWeight.Medium,
            letterSpacing = 0.4.sp,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(if (selecionado) 1f else 0.75f),
        )
    }
}
