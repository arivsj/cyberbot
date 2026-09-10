package com.cyberbot.mobile.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cyberbot.mobile.ui.chat.ChatRoute
import com.cyberbot.mobile.ui.cleanup.CleanupRoute
import com.cyberbot.mobile.ui.dashboard.DashboardRoute
import com.cyberbot.mobile.ui.devices.DevicesRoute
import com.cyberbot.mobile.ui.diagnostics.DiagnosticsRoute
import com.cyberbot.mobile.ui.drive.DriveRoute
import com.cyberbot.mobile.ui.finance.FinanceRoute
import com.cyberbot.mobile.ui.home.HomeRoute
import com.cyberbot.mobile.ui.lock.LockRoute
import com.cyberbot.mobile.ui.lock.LockViewModel
import com.cyberbot.mobile.ui.model.ModelRoute
import com.cyberbot.mobile.ui.pairing.PairingRoute
import com.cyberbot.mobile.ui.pcapps.PcAppsRoute
import com.cyberbot.mobile.ui.plugins.PluginsRoute
import com.cyberbot.mobile.ui.rag.RagRoute
import com.cyberbot.mobile.ui.security.SecurityRoute
import com.cyberbot.mobile.ui.trilha.TrilhaRoute
import com.cyberbot.mobile.ui.websearch.WebSearchRoute
import com.cyberbot.mobile.ui.youtube.YoutubeRoute

object Routes {
    const val Pairing = "pairing"
    const val Home = "home"
    const val Dashboard = "dashboard"
    const val Chat = "chat"
    const val PcApps = "pcapps"
    const val Drive = "drive"
    const val Finance = "finance"
    const val Security = "security"
    const val Cleanup = "cleanup"
    const val Devices = "devices"
    const val Diagnostics = "diagnostics"
    const val Trilha = "trilha"
    const val Youtube = "youtube"
    const val Rag = "rag"
    const val WebSearch = "websearch"
    const val Plugins = "plugins"
    const val Model = "model"
}

private val bottomDestinations = listOf(
    CyberNavItem(Routes.Home, "Home", Icons.Filled.GridView),
    CyberNavItem(Routes.Chat, "Chat", Icons.Filled.Forum),
    CyberNavItem(Routes.PcApps, "PC Apps", Icons.Filled.DesktopWindows),
    CyberNavItem(Routes.Dashboard, "Status", Icons.Filled.MonitorHeart),
)

private fun androidx.navigation.NavHostController.goSingleTop(route: String) {
    navigate(route) { launchSingleTop = true }
}

/**
 * Raiz do app. Nada e montado antes da autenticacao: enquanto a tela de bloqueio
 * estiver no ar, nem o NavHost nem o menu inferior existem na composicao.
 */
@Composable
fun CyberBotNavRoot(
    viewModel: RootViewModel = hiltViewModel(),
    lockViewModel: LockViewModel = hiltViewModel(),
) {
    val navController = rememberNavController()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val liberado by lockViewModel.liberado.collectAsStateWithLifecycle()
    val overlayBloqueio by lockViewModel.overlay.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentRoute = currentDestination?.route

    // Voltar do segundo plano pede autenticacao de novo. Se o app so foi para tras
    // por causa do proprio prompt do sistema, nao ha o que refazer.
    val cicloDeVida = LocalLifecycleOwner.current
    var foiParaSegundoPlano by remember { mutableStateOf(false) }
    DisposableEffect(cicloDeVida, liberado) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_STOP -> if (liberado) foiParaSegundoPlano = true
                Lifecycle.Event.ON_START -> {
                    if (foiParaSegundoPlano && liberado) lockViewModel.relockar()
                    foiParaSegundoPlano = false
                }

                else -> Unit
            }
        }
        cicloDeVida.lifecycle.addObserver(observador)
        onDispose { cicloDeVida.lifecycle.removeObserver(observador) }
    }

    // So navega depois do desbloqueio: antes disso o NavHost nem existe e o
    // navController ainda esta sem grafo.
    LaunchedEffect(session.isPaired, liberado) {
        if (!liberado) return@LaunchedEffect
        val target = if (session.isPaired) Routes.Home else Routes.Pairing
        navController.navigate(target) {
            popUpTo(navController.graph.findStartDestination().id) {
                inclusive = true
            }
            launchSingleTop = true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (liberado) {
            Scaffold(
                bottomBar = {
                    if (session.isPaired && currentRoute != Routes.Pairing) {
                        CyberBottomBar(
                            items = bottomDestinations,
                            selectedRoute = bottomDestinations
                                .firstOrNull { destino ->
                                    currentDestination?.hierarchy?.any { it.route == destino.route } == true
                                }
                                ?.route,
                            onSelect = { route ->
                                navController.navigate(route) {
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                },
            ) { innerPadding ->
                NavHost(
                    navController = navController,
                    startDestination = Routes.Pairing,
                    // Entrada com deslize curto + fade no lugar do fade seco padrao. O deslocamento
                    // e de 1/12 da tela para dar profundidade sem parecer pesado.
                    enterTransition = {
                        fadeIn(animationSpec = tween(200)) +
                            slideInHorizontally(animationSpec = tween(300)) { largura -> largura / 12 }
                    },
                    exitTransition = {
                        fadeOut(animationSpec = tween(140)) +
                            slideOutHorizontally(animationSpec = tween(300)) { largura -> -largura / 14 }
                    },
                    popEnterTransition = {
                        fadeIn(animationSpec = tween(200)) +
                            slideInHorizontally(animationSpec = tween(300)) { largura -> -largura / 12 }
                    },
                    popExitTransition = {
                        fadeOut(animationSpec = tween(140)) +
                            slideOutHorizontally(animationSpec = tween(300)) { largura -> largura / 14 }
                    },
                    // consumeWindowInsets: o padding do Scaffold de fora (barra inferior + barra de
                    // status) e dado aqui e marcado como ja consumido. Sem isso o Scaffold de dentro
                    // de cada tela soma o inset de novo e sobra uma faixa morta de ~50px acima do menu.
                    modifier = Modifier
                        .padding(innerPadding)
                        .consumeWindowInsets(innerPadding),
                ) {
                    composable(Routes.Pairing) {
                        PairingRoute()
                    }
                    composable(Routes.Home) {
                        HomeRoute(
                            onOpenDestination = { navController.goSingleTop(it) },
                        )
                    }
                    composable(Routes.Dashboard) {
                        DashboardRoute()
                    }
                    composable(Routes.Chat) {
                        ChatRoute()
                    }
                    composable(Routes.PcApps) {
                        PcAppsRoute()
                    }
                    composable(Routes.Drive) {
                        DriveRoute()
                    }
                    composable(Routes.Finance) {
                        FinanceRoute()
                    }
                    composable(Routes.Security) {
                        SecurityRoute()
                    }
                    composable(Routes.Cleanup) {
                        CleanupRoute()
                    }
                    composable(Routes.Devices) {
                        DevicesRoute()
                    }
                    composable(Routes.Diagnostics) {
                        DiagnosticsRoute()
                    }
                    composable(Routes.Trilha) {
                        TrilhaRoute()
                    }
                    composable(Routes.Youtube) {
                        YoutubeRoute()
                    }
                    composable(Routes.Rag) {
                        RagRoute()
                    }
                    composable(Routes.WebSearch) {
                        WebSearchRoute()
                    }
                    composable(Routes.Plugins) {
                        PluginsRoute()
                    }
                    composable(Routes.Model) {
                        ModelRoute()
                    }
                }
            }
        }

        if (overlayBloqueio) {
            LockRoute(
                onDesbloqueado = { lockViewModel.confirmarEntrada() },
                onSumiu = { lockViewModel.finalizarSaida() },
            )
        }
    }
}
