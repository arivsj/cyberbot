package com.cyberbot.mobile.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyberbot.mobile.ui.theme.Border
import com.cyberbot.mobile.ui.theme.Danger
import com.cyberbot.mobile.ui.theme.JetBrainsMono
import com.cyberbot.mobile.ui.theme.Neon
import com.cyberbot.mobile.ui.theme.Neon3
import com.cyberbot.mobile.ui.theme.TextDim

/** Verde/ciano ate 60%, laranja ate 85%, vermelho acima. */
fun corPorUso(percentual: Double?): Color = when {
    percentual == null -> TextDim
    percentual >= 85.0 -> Danger
    percentual >= 60.0 -> Neon3
    else -> Neon
}

/**
 * Uma linha "rotulo -> valor" alinhada nas duas pontas, com o valor em fonte mono
 * e cor de destaque. Substitui as linhas soltas de texto que ficavam todas iguais.
 */
@Composable
fun StatRow(
    rotulo: String,
    valor: String,
    modifier: Modifier = Modifier,
    corValor: Color = Neon,
    divisor: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = rotulo,
                style = MaterialTheme.typography.labelMedium,
                color = TextDim,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valor,
                fontFamily = JetBrainsMono,
                fontWeight = FontWeight.Medium,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                color = corValor,
                maxLines = 1,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
        if (divisor) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Border.copy(alpha = 0.45f)),
            )
        }
    }
}

/** Estado vazio das listas, no lugar do texto solto "Sem dados". */
@Composable
fun EmptyState(
    icone: ImageVector,
    texto: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = icone,
            contentDescription = null,
            tint = TextDim,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = texto,
            style = MaterialTheme.typography.bodySmall,
            color = TextDim,
            textAlign = TextAlign.Center,
        )
    }
}
