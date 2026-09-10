package com.cyberbot.mobile.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyberbot.mobile.ui.common.PressableCyberCard
import com.cyberbot.mobile.ui.common.ScreenScaffold
import com.cyberbot.mobile.ui.nav.Routes
import kotlin.math.ceil
import kotlinx.coroutines.delay

private const val COLUNAS = 3
private val ESPACO = 12.dp
private val ALTURA_MINIMA = 104.dp

/** De que lado cada cartao entra na formacao da Home. */
private fun direcaoDeEntrada(indice: Int): Offset {
    val coluna = indice % COLUNAS
    val linha = indice / COLUNAS
    return when (coluna) {
        0 -> Offset(-1f, 0f)
        COLUNAS - 1 -> Offset(1f, 0f)
        else -> if (linha % 2 == 0) Offset(0f, -1f) else Offset(0f, 1f)
    }
}

private data class HomeModule(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val homeModules = listOf(
    HomeModule(Routes.Diagnostics, "Diagnóstico", Icons.Filled.MonitorHeart),
    HomeModule(Routes.Drive, "Drive", Icons.Filled.Folder),
    HomeModule(Routes.Finance, "Finanças", Icons.Filled.AccountBalanceWallet),
    HomeModule(Routes.Security, "Security", Icons.Filled.Shield),
    HomeModule(Routes.Cleanup, "Limpeza", Icons.Filled.CleaningServices),
    HomeModule(Routes.Trilha, "Trilha Rede", Icons.Filled.Hub),
    HomeModule(Routes.Youtube, "YouTube", Icons.Filled.SmartDisplay),
    HomeModule(Routes.WebSearch, "Web Search", Icons.Filled.TravelExplore),
    HomeModule(Routes.Rag, "RAG (docs)", Icons.Filled.LibraryBooks),
    HomeModule(Routes.Plugins, "Plugins", Icons.Filled.Extension),
    HomeModule(Routes.Model, "Trocar modelo", Icons.Filled.Memory),
    HomeModule(Routes.Devices, "Dispositivos", Icons.Filled.Devices),
)

@Composable
fun HomeRoute(
    onOpenDestination: (String) -> Unit,
) {
    HomeScreen(onOpenDestination = onOpenDestination)
}

@Composable
fun HomeScreen(
    onOpenDestination: (String) -> Unit,
) {
    ScreenScaffold(title = "Home") { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val linhas = ceil(homeModules.size / COLUNAS.toFloat()).toInt().coerceAtLeast(1)
            // Distribui a altura disponível entre as linhas: a grade termina logo acima do
            // menu inferior, sem sobrar espaço vazio embaixo.
            val alturaCelula = ((maxHeight - ESPACO * (linhas - 1) - 32.dp) / linhas)
                .coerceAtLeast(ALTURA_MINIMA)

            LazyVerticalGrid(
                columns = GridCells.Fixed(COLUNAS),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(ESPACO),
                verticalArrangement = Arrangement.spacedBy(ESPACO),
            ) {
                itemsIndexed(homeModules) { indice, module ->
                    // Entrada em cascata: cada cartao vem de um lado diferente e se
                    // acomoda no lugar, formando a grade depois do desbloqueio.
                    var chegou by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) {
                        delay(indice * 42L)
                        chegou = true
                    }
                    val entrada by animateFloatAsState(
                        targetValue = if (chegou) 0f else 1f,
                        animationSpec = spring(
                            dampingRatio = 0.62f,
                            stiffness = Spring.StiffnessLow,
                        ),
                        label = "entradaCartao",
                    )
                    val direcao = direcaoDeEntrada(indice)

                    PressableCyberCard(
                        onClick = { onOpenDestination(module.route) },
                        modifier = Modifier
                            .height(alturaCelula)
                            .graphicsLayer {
                                translationX = direcao.x * entrada * 320f
                                translationY = direcao.y * entrada * 320f
                                alpha = (1f - entrada).coerceIn(0f, 1f)
                            },
                        contentPadding = PaddingValues(0.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                        contentModifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            imageVector = module.icon,
                            contentDescription = module.label,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(42.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = module.label,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
