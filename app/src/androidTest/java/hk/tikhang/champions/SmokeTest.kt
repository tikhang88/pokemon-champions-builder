package hk.tikhang.champions

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 8 UI smoke tests: the app starts offline from bundled data and every main screen opens. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun waitFor(text: String, timeout: Long = 20_000) =
        rule.waitUntilAtLeastOneExists(hasText(text, substring = true), timeout)

    private fun openFromHome(title: String) {
        waitFor("資料狀態")
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(title))
        rule.onAllNodesWithText(title).onFirst().performClick()
    }

    @Test fun homeLoadsBundledData() {
        waitFor("資料狀態")
        rule.onNodeWithText("Pokémon Champion 隊伍模擬器").assertExists()
        rule.onAllNodesWithText("離線可用", substring = true).onFirst().assertExists()
    }

    @Test fun pokedexOpens() {
        openFromHome("Pokémon 圖鑑")
        waitFor(" / ")   // subtitle "Regulation・shown / total"
    }

    @Test fun createTeamAndOpenTools() {
        openFromHome("Team Builder")
        waitFor("建立新隊伍")
        rule.onNodeWithText("建立新隊伍").performClick()
        waitFor("合法性")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        waitFor("隊伍 1")
    }

    @Test fun damageCalculatorOpens() {
        openFromHome("傷害計算")
        waitFor("Lv50")
    }

    @Test fun speedTierOpens() {
        openFromHome("Speed Tier 研究")
        waitFor("隻")
    }

    @Test fun speedLinesOpens() {
        openFromHome("速度線")
        waitFor("顯示的速度線")
        waitFor("極速")
    }

    @Test fun dataScreenShowsOnlineUpdate() {
        openFromHome("資料更新")
        waitFor("線上更新")
        waitFor("自動更新")
    }

    @Test fun regulationScreenOpens() {
        openFromHome("Regulation")
        waitFor("M-C")
    }
}
