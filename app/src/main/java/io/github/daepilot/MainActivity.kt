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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.haze
import dev.chrisbanes.haze.hazeChild
import io.github.daepilot.ui.AppViewModel
import io.github.daepilot.ui.components.AmbientBackground
import io.github.daepilot.ui.screens.HomeScreen
import io.github.daepilot.ui.screens.LogsScreen
import io.github.daepilot.ui.screens.NodesScreen
import io.github.daepilot.ui.screens.RoutingScreen
import io.github.daepilot.ui.screens.SettingsScreen
import io.github.daepilot.ui.screens.SubscriptionsScreen
import io.github.daepilot.ui.theme.AmbientMode
import io.github.daepilot.ui.theme.DaePilotTheme
import io.github.daepilot.ui.theme.LocalDaeGlassPalette

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
            DaePilotTheme(
                themeMode = snapshot.settings.themeMode,
                accentColor = snapshot.settings.accentColor,
            ) {
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
    val selectedIcon: ImageVector,
)

private val tabs = listOf(
    Tab("home", "首页", Icons.Outlined.Home, Icons.Filled.Home),
    Tab("subscriptions", "订阅", Icons.Outlined.Subscriptions, Icons.Filled.Subscriptions),
    Tab("nodes", "节点", Icons.Outlined.Dns, Icons.Filled.Dns),
    Tab("routing", "分流", Icons.AutoMirrored.Outlined.AltRoute, Icons.AutoMirrored.Filled.AltRoute),
    Tab("settings", "设置", Icons.Outlined.Settings, Icons.Filled.Settings),
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
    val hazeState = remember { HazeState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val openLogs = {
        navController.navigate("logs") { launchSingleTop = true }
    }
    val ambientMode = when {
        status.running -> AmbientMode.Running
        status.mode == "no-root" || status.mode == "module-missing" -> AmbientMode.Setup
        else -> AmbientMode.Idle
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(bottom = 92.dp),
            )
        },
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .haze(hazeState),
            ) {
                AmbientBackground(mode = ambientMode)
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding(),
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

            if (currentRoute in tabRoutes) {
                DaeBottomBar(
                    currentRoute = currentRoute,
                    hazeState = hazeState,
                    onNavigate = { navController.navigateToTab(it) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
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

@Composable
private fun DaeBottomBar(
    currentRoute: String?,
    hazeState: HazeState,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalDaeGlassPalette.current
    val shape = RoundedCornerShape(28.dp)
    val style = HazeStyle(
        backgroundColor = MaterialTheme.colorScheme.surface,
        tints = listOf(HazeTint(palette.dockTint)),
        blurRadius = 24.dp,
        noiseFactor = 0.04f,
        fallbackTint = HazeTint(palette.fallbackTint),
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = 16.dp,
                shape = shape,
                clip = false,
                ambientColor = palette.shadow,
                spotColor = palette.shadow,
            )
            .clip(shape)
            .hazeChild(hazeState, style)
            .border(
                0.75.dp,
                Brush.verticalGradient(listOf(palette.borderTop, palette.borderBottom)),
                shape,
            )
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .height(58.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            DockItem(
                tab = tab,
                selected = currentRoute == tab.route,
                onClick = { onNavigate(tab.route) },
            )
        }
    }
}

@Composable
private fun RowScope.DockItem(
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        targetValue = if (selected) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
        } else {
            Color.Transparent
        },
        animationSpec = tween(220),
        label = "dockItemBackground",
    )
    val foreground = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val iconScale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = 520f),
        label = "dockIconScale",
    )
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) tab.selectedIcon else tab.icon,
            contentDescription = tab.label,
            tint = foreground,
            modifier = Modifier
                .size(21.dp)
                .graphicsLayer {
                    scaleX = iconScale
                    scaleY = iconScale
                },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = foreground,
        )
    }
}
