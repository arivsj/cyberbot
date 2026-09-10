package com.cyberbot.mobile.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ScreenScaffold
import com.cyberbot.mobile.ui.nav.Routes

private const val COLUNAS = 3
private val ESPACO = 12.dp

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
        // Cards com tamanho fixo (quadrados): novos módulos entram embaixo e a grade rola.
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUNAS),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(ESPACO),
            verticalArrangement = Arrangement.spacedBy(ESPACO),
        ) {
            items(homeModules) { module ->
                CyberCard(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clickable { onOpenDestination(module.route) },
                ) {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
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
