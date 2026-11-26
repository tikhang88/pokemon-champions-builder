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
import hk.tikhang.champions.ui.SlotEditorScreen
import hk.tikhang.champions.ui.TeamAnalysisScreen
import hk.tikhang.champions.ui.SpeedTierScreen
import hk.tikhang.champions.ui.DamageCalcScreen
import hk.tikhang.champions.ui.BattleSimScreen
import hk.tikhang.champions.ui.MatchupScreen
import hk.tikhang.champions.ui.TeamEditorScreen
import hk.tikhang.champions.ui.TeamsScreen
import hk.tikhang.champions.core.TeamEngine

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
    const val TEAMS = "teams"
    const val TEAM = "team/{id}"
    const val PICK = "team/{id}/pick/{slot}"
    const val SLOT = "team/{id}/slot/{slot}"
    const val ANALYSIS = "team/{id}/analysis"
    const val SPEED = "speed/{id}"
    const val CALC = "calc/{id}"
    const val BATTLE = "team/{id}/battle"
    fun battle(id: Long) = "team/$id/battle"
    fun calc(id: Long) = "calc/$id"
    const val MATCHUP = "team/{id}/matchup"
    fun speed(id: Long) = "speed/$id"
    fun matchup(id: Long) = "team/$id/matchup"
    fun analysis(id: Long) = "team/$id/analysis"
    fun team(id: Long) = "team/$id"
    fun pick(id: Long, slot: Int) = "team/$id/pick/$slot"
    fun slot(id: Long, slot: Int) = "team/$id/slot/$slot"
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
        composable(Routes.TEAMS) { TeamsScreen(vm, state, onBack = back) { nav.navigate(Routes.team(it)) } }
        composable(Routes.TEAM, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            TeamEditorScreen(vm, state, id, onBack = back,
                pickPokemon = { nav.navigate(Routes.pick(id, it)) }, editSlot = { nav.navigate(Routes.slot(id, it)) },
                analyse = { nav.navigate(Routes.analysis(id)) },
                speedTier = { nav.navigate(Routes.speed(id)) }, matchup = { nav.navigate(Routes.matchup(id)) },
                damage = { nav.navigate(Routes.calc(id)) }, battle = { nav.navigate(Routes.battle(id)) })
        }
        composable(Routes.BATTLE, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            BattleSimScreen(vm, state, entry.arguments?.getLong("id") ?: 0L, onBack = back)
        }
        composable(Routes.CALC, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            DamageCalcScreen(vm, state, entry.arguments?.getLong("id") ?: 0L, onBack = back)
        }
        composable(Routes.SPEED, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            SpeedTierScreen(vm, state, entry.arguments?.getLong("id") ?: 0L, onBack = back) { nav.navigate(Routes.pokemon(it)) }
        }
        composable(Routes.MATCHUP, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            MatchupScreen(vm, state, entry.arguments?.getLong("id") ?: 0L, onBack = back) { nav.navigate(Routes.pokemon(it)) }
        }
        composable(Routes.ANALYSIS, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
            TeamAnalysisScreen(vm, state, entry.arguments?.getLong("id") ?: 0L, onBack = back) { nav.navigate(Routes.pokemon(it)) }
        }
        composable(Routes.PICK, arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("slot") { type = NavType.IntType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val slotIndex = entry.arguments?.getInt("slot") ?: 0
            val teams by vm.teams.collectAsStateWithLifecycle()
            val team = teams.firstOrNull { it.id == id }?.team()
            val reg = team?.let { state.snapshot.regulationById[it.regulationId] } ?: state.regulation
            PokemonListScreen(state, onBack = back, pickMode = true, regulation = reg) { speciesId ->
                if (team != null) {
                    vm.saveTeam(id, team.withSlot(slotIndex, TeamEngine.newSlot(state.snapshot, speciesId)))
                    nav.navigate(Routes.slot(id, slotIndex)) { popUpTo(Routes.TEAM) { inclusive = false } }
                }
            }
        }
        composable(Routes.SLOT, arguments = listOf(navArgument("id") { type = NavType.LongType }, navArgument("slot") { type = NavType.IntType })) { entry ->
            val id = entry.arguments?.getLong("id") ?: 0L
            val slotIndex = entry.arguments?.getInt("slot") ?: 0
            SlotEditorScreen(vm, state, id, slotIndex, onBack = back) { nav.navigate(Routes.pick(id, slotIndex)) }
        }
    }
}
