package hk.tikhang.champions

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import hk.tikhang.champions.data.AppViewModel
import hk.tikhang.champions.ui.ChampionsTheme
import hk.tikhang.champions.ui.DataUpdateScreen
import hk.tikhang.champions.ui.HomeScreen
import hk.tikhang.champions.ui.PokemonDetailScreen
import hk.tikhang.champions.ui.PokemonListScreen
import hk.tikhang.champions.ui.RegulationDetailScreen
import hk.tikhang.champions.ui.RegulationListScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ChampionsTheme {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

object Routes {
    const val HOME = "home"
    const val POKEDEX = "pokedex"
    const val POKEMON = "pokemon/{id}"
    const val REGULATIONS = "regulations"
    const val REGULATION = "regulation/{id}"
    const val DATA = "data"
    fun pokemon(id: String) = "pokemon/$id"
    fun regulation(id: String) = "regulation/$id"
}

@Composable
private fun App(vm: AppViewModel = viewModel()) {
    val data by vm.data.collectAsStateWithLifecycle()
    val warning by vm.startupWarning.collectAsStateWithLifecycle()
    val state = data
    if (state == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    warning?.let {
        AlertDialog(
            onDismissRequest = vm::clearStartupWarning,
            confirmButton = { TextButton(onClick = vm::clearStartupWarning) { Text("知道") } },
            title = { Text("資料提示") }, text = { Text(it) },
        )
    }
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) { HomeScreen(vm, state) { nav.navigate(it) } }
        composable(Routes.POKEDEX) { PokemonListScreen(state, onBack = back) { nav.navigate(Routes.pokemon(it)) } }
        composable(Routes.POKEMON, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            PokemonDetailScreen(state, entry.arguments?.getString("id").orEmpty(), onBack = back) { nav.navigate(Routes.pokemon(it)) }
        }
        composable(Routes.REGULATIONS) { RegulationListScreen(vm, state, onBack = back) { nav.navigate(Routes.regulation(it)) } }
        composable(Routes.REGULATION, arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
            RegulationDetailScreen(vm, state, entry.arguments?.getString("id").orEmpty(), onBack = back) { nav.navigate(Routes.pokemon(it)) }
        }
        composable(Routes.DATA) { DataUpdateScreen(vm, state, onBack = back) }
    }
}
