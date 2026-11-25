package com.seojin.assetguardian.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.first
import com.seojin.assetguardian.ui.screens.AddProductScreen
import com.seojin.assetguardian.ui.screens.AiPlanScreen
import com.seojin.assetguardian.ui.screens.AnalysisScreen
import com.seojin.assetguardian.ui.screens.ExploreScreen
import com.seojin.assetguardian.ui.screens.GoalScreen
import com.seojin.assetguardian.ui.screens.HomeScreen
import com.seojin.assetguardian.ui.screens.InboxScreen
import com.seojin.assetguardian.ui.screens.MemoryScreen
import com.seojin.assetguardian.ui.screens.ChatScreen
import com.seojin.assetguardian.ui.screens.CustomProductScreen
import com.seojin.assetguardian.ui.screens.TasksScreen
import com.seojin.assetguardian.ui.screens.GoalTrackScreen
import com.seojin.assetguardian.ui.screens.WeeklyScreen
import com.seojin.assetguardian.ui.screens.PlanScreen
import com.seojin.assetguardian.ui.screens.SettingsScreen
import com.seojin.assetguardian.ui.theme.Palette

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("home", "내 적금", Icons.Filled.Home),
    Tab("explore", "상품 탐색", Icons.Filled.Search),
    Tab("inbox", "알림함", Icons.Filled.Notifications),
    Tab("goal", "목표", Icons.Filled.Star),
    Tab("chat", "AI 상담", Icons.Filled.Face),
)

@Composable
fun AppRoot(startTab: String?, onStartHandled: () -> Unit = {}, vm: MainViewModel = viewModel()) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val message by vm.message.collectAsStateWithLifecycle()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route?.substringBefore("?")

    fun go(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    // 알림을 눌러 들어오면 해당 화면으로 (우대조건 / 목표 진행 / 주간 리포트)
    LaunchedEffect(startTab) {
        val target = startTab ?: return@LaunchedEffect
        nav.currentBackStackEntryFlow.first()          // NavHost가 그래프를 만든 뒤에 이동 (먼저 이동하면 앱이 꺼짐)
        when (target) {
            "inbox", "chat" -> go(target)
            "tasks", "goaltrack", "weekly" -> nav.navigate(target) { launchSingleTop = true }
        }
        onStartHandled()                               // 한 번만 이동 (화면 회전·재진입 때 다시 이동하지 않게)
    }

    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }


    Scaffold(
        containerColor = Palette.Bg,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar(containerColor = Palette.Surface) {
                    tabs.forEach { t ->
                        NavigationBarItem(
                            selected = route == t.route,
                            onClick = { go(t.route) },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Palette.MintDeep,
                                selectedTextColor = Palette.MintDeep,
                                indicatorColor = Palette.MintSoft
                            )
                        )
                    }
                }
            }
        }
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = "home",
            modifier = Modifier.padding(inner)
        ) {
            composable("home") {
                HomeScreen(vm,
                    onAdd = { nav.navigate("add?code=&term=0") },
                    onCompare = { myId -> nav.navigate("explore?my=${Uri.encode(myId)}") },
                    onSettings = { nav.navigate("settings") },
                    onGoal = { go("goal") },
                    onAiPlan = { nav.navigate("aiplan") },
                    onMemory = { nav.navigate("memory") },
                    onAgentScreen = { r -> nav.navigate(r) },
                    onPlan = { t, sv, mx -> nav.navigate("plan?target=$t&saving=$sv&max=$mx") })
            }
            composable(
                "explore?my={my}",
                arguments = listOf(navArgument("my") { type = NavType.StringType; defaultValue = "" })
            ) { e ->
                ExploreScreen(vm, initialMyId = e.arguments?.getString("my") ?: "",
                    onOpen = { myId, key -> nav.navigate("analysis?my=${Uri.encode(myId)}&key=${Uri.encode(key)}") },
                    onCustom = { nav.navigate("custom") })
            }
            composable("inbox") { InboxScreen(vm) }
            composable("goal") {
                GoalScreen(vm,
                    onOpenPlan = { t, sv, mx -> nav.navigate("plan?target=$t&saving=$sv&max=$mx") },
                    onOpenAiPlan = { nav.navigate("aiplan") })
            }
            composable(
                "plan?target={target}&saving={saving}&max={max}",
                arguments = listOf(
                    navArgument("target") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("saving") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("max") { type = NavType.BoolType; defaultValue = false }
                )
            ) { e ->
                PlanScreen(vm,
                    target = e.arguments?.getLong("target") ?: 0L,
                    saving = e.arguments?.getLong("saving") ?: 0L,
                    useMax = e.arguments?.getBoolean("max") ?: false,
                    onBack = { nav.popBackStack() })
            }
            composable("chat") { ChatScreen(vm) }
            composable("custom") {
                CustomProductScreen(vm, onBack = { nav.popBackStack() }, onOpen = { p ->
                    // 같은 기간의 내 적금이 있으면 그걸 기준으로 비교 (없으면 첫 번째 적금)
                    val mine = vm.myProducts.value
                    val my = mine.firstOrNull { it.termMonths == p.termMonths } ?: mine.firstOrNull()
                    nav.navigate("analysis?my=${Uri.encode(my?.id ?: "")}&key=${Uri.encode(p.key)}")
                })
            }
            composable("tasks") { TasksScreen(vm, onBack = { nav.popBackStack() }) }
            composable("goaltrack") {
                GoalTrackScreen(vm, onBack = { nav.popBackStack() },
                    onAiPlan = { nav.navigate("aiplan") }, onGoalTab = { go("goal") })
            }
            composable("weekly") {
                WeeklyScreen(vm, onBack = { nav.popBackStack() },
                    onOpenProduct = { p -> nav.navigate("analysis?my=&key=${Uri.encode(p.key)}") })
            }
            composable("memory") {
                MemoryScreen(vm, onBack = { nav.popBackStack() },
                    onOpenProduct = { p -> nav.navigate("analysis?my=&key=${Uri.encode(p.key)}") })
            }
            composable("aiplan") {
                AiPlanScreen(vm, onBack = { nav.popBackStack() }, onRetry = { go("goal") })
            }
            composable("settings") { SettingsScreen(vm, onBack = { nav.popBackStack() }) }
            composable(
                "add?code={code}&term={term}",
                arguments = listOf(
                    navArgument("code") { type = NavType.StringType; defaultValue = "" },
                    navArgument("term") { type = NavType.IntType; defaultValue = 0 }
                )
            ) { e ->
                AddProductScreen(vm,
                    prefillKey = e.arguments?.getString("code") ?: "",
                    onDone = { nav.popBackStack() })
            }
            composable(
                "analysis?my={my}&key={key}",
                arguments = listOf(
                    navArgument("my") { type = NavType.StringType; defaultValue = "" },
                    navArgument("key") { type = NavType.StringType; defaultValue = "" }
                )
            ) { e ->
                AnalysisScreen(vm,
                    myId = e.arguments?.getString("my") ?: "",
                    key = e.arguments?.getString("key") ?: "",
                    onBack = { nav.popBackStack() },
                    onRegister = { key -> nav.navigate("add?code=${Uri.encode(key)}&term=0") })
            }
        }
    }
}
