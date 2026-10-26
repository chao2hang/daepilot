package io.github.daepilot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.screens.HomeScreen
import io.github.daepilot.ui.screens.LogsScreen
import io.github.daepilot.ui.screens.NodesScreen
import io.github.daepilot.ui.screens.RoutingScreen
import io.github.daepilot.ui.screens.SettingsScreen
import io.github.daepilot.ui.screens.SubscriptionsScreen
import io.github.daepilot.ui.theme.DaePilotTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels {
        AppViewModel.Factory(application)
    }

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
            DaePilotTheme(themeMode = snapshot.settings.themeMode) {
                DaePilotRoot(viewModel)
            }
        }
        requestNotificationPermission()
        viewModel.ensureForegroundService()
        viewModel.refreshStatus()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs = listOf(
    Tab("home", "首页", Icons.Outlined.Home),
    Tab("subscriptions", "订阅", Icons.Outlined.Subscriptions),
    Tab("nodes", "节点", Icons.Outlined.Dns),
    Tab("routing", "分流", Icons.AutoMirrored.Outlined.AltRoute),
    Tab("settings", "设置", Icons.Outlined.Settings),
)

private val tabRoutes = tabs.map { it.route }.toSet()

@Composable
private fun DaePilotRoot(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val compatibility by viewModel.compatibility.collectAsStateWithLifecycle()
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val upload by viewModel.logUpload.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val openLogs = {
        navController.navigate("logs") { launchSingleTop = true }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (currentRoute in tabRoutes) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                    ) {
                        tabs.forEach { tab ->
                            NavigationBarItem(
                                selected = currentRoute == tab.route,
                                onClick = { navController.navigateToTab(tab.route) },
                                icon = { Icon(tab.icon, contentDescription = null) },
                                label = { Text(tab.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            composable("home") {
                HomeScreen(
                    viewModel = viewModel,
                    snapshot = snapshot,
                    status = status,
                    busy = busy,
                    compatibility = compatibility,
                    onOpenNodes = { navController.navigateToTab("nodes") },
                    onOpenLogs = openLogs,
                    onOpenSettings = { navController.navigateToTab("settings") },
                    onOpenSubscriptions = { navController.navigateToTab("subscriptions") },
                )
            }
            composable("subscriptions") {
                SubscriptionsScreen(viewModel = viewModel, snapshot = snapshot, busy = busy)
            }
            composable("nodes") {
                NodesScreen(viewModel = viewModel, snapshot = snapshot, busy = busy)
            }
            composable("routing") {
                RoutingScreen(viewModel = viewModel, snapshot = snapshot, busy = busy)
            }
            composable("logs") {
                LogsScreen(
                    viewModel = viewModel,
                    logs = logs,
                    upload = upload,
                    onBack = { navController.popBackStack() },
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    snapshot = snapshot,
                    status = status,
                    compatibility = compatibility,
                    onOpenLogs = openLogs,
                )
            }
        }
    }
}

private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
