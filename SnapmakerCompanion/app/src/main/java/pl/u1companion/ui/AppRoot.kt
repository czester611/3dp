package pl.u1companion.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryBooks
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.u1companion.core.model.SearchQuery
import pl.u1companion.core.model.SourceId
import pl.u1companion.ui.browse.BrowseScreen
import pl.u1companion.ui.detail.DetailScreen
import pl.u1companion.ui.library.LibraryScreen
import pl.u1companion.ui.print.PrintSetupScreen
import pl.u1companion.ui.printers.PrintersScreen
import pl.u1companion.ui.settings.SettingsScreen
import pl.u1companion.ui.web.WebSourceScreen

// Type-safe trasy nawigacji (Navigation Compose 2.8+).
@Serializable data class BrowseRoute(val queryJson: String? = null)
@Serializable data class DetailRoute(val source: String, val modelId: String)
@Serializable data class PrintSetupRoute(val libraryFileId: Long)
@Serializable data class WebSourceRoute(val source: String, val url: String, val modelKey: String? = null)
@Serializable data object LibraryRoute
@Serializable data object PrintersRoute
@Serializable data object SettingsRoute

private data class TopLevel(val label: String, val icon: ImageVector, val route: Any, val matches: (androidx.navigation.NavDestination) -> Boolean)

private val routeJson = Json { ignoreUnknownKeys = true }

@Composable
fun AppRoot() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val dest = backStack?.destination

    @Suppress("DEPRECATION")
    val tops = listOf(
        TopLevel("Odkrywaj", Icons.Default.Explore, BrowseRoute()) { it.hasRoute(BrowseRoute::class) },
        TopLevel("Biblioteka", Icons.Default.LibraryBooks, LibraryRoute) { it.hasRoute(LibraryRoute::class) },
        TopLevel("Drukarki", Icons.Default.Print, PrintersRoute) { it.hasRoute(PrintersRoute::class) },
        TopLevel("Ustawienia", Icons.Default.Settings, SettingsRoute) { it.hasRoute(SettingsRoute::class) },
    )

    Scaffold(
        bottomBar = {
            if (dest == null || tops.any { it.matches(dest) }) {
                NavigationBar {
                    tops.forEach { t ->
                        NavigationBarItem(
                            selected = dest?.let(t.matches) == true,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(t.icon, null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            }
        },
    ) { pad ->
        NavHost(nav, startDestination = BrowseRoute(), modifier = Modifier.padding(pad)) {
            composable<BrowseRoute> { entry ->
                val r = entry.toRoute<BrowseRoute>()
                BrowseScreen(
                    onOpenModel = { m -> nav.navigate(DetailRoute(m.source.name, m.id)) },
                    onOpenWebSource = { s, url -> nav.navigate(WebSourceRoute(s.name, url)) },
                    initialQuery = r.queryJson?.let { runCatching { routeJson.decodeFromString(SearchQuery.serializer(), it) }.getOrNull() },
                )
            }
            composable<DetailRoute> { entry ->
                val r = entry.toRoute<DetailRoute>()
                DetailScreen(
                    source = SourceId.valueOf(r.source),
                    modelId = r.modelId,
                    onBack = { nav.popBackStack() },
                    onPrint = { id -> nav.navigate(PrintSetupRoute(id)) },
                    onOpenWeb = { s, url, key -> nav.navigate(WebSourceRoute(s.name, url, key)) },
                )
            }
            composable<PrintSetupRoute> { entry ->
                PrintSetupScreen(entry.toRoute<PrintSetupRoute>().libraryFileId, onBack = { nav.popBackStack() })
            }
            composable<WebSourceRoute> { entry ->
                val r = entry.toRoute<WebSourceRoute>()
                WebSourceScreen(SourceId.valueOf(r.source), r.url, onBack = { nav.popBackStack() }, modelKey = r.modelKey)
            }
            composable<LibraryRoute> {
                LibraryScreen(
                    onOpenModel = { s, id -> nav.navigate(DetailRoute(s.name, id)) },
                    onPrintFile = { id -> nav.navigate(PrintSetupRoute(id)) },
                    onRunSearch = { q -> nav.navigate(BrowseRoute(routeJson.encodeToString(SearchQuery.serializer(), q))) },
                )
            }
            composable<PrintersRoute> { PrintersScreen() }
            composable<SettingsRoute> { SettingsScreen() }
        }
    }
}
