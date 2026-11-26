package hk.tikhang.champions.core

import hk.tikhang.champions.core.DamageEngine.Status
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Battle Simulation Engine (spec §13). Independent of the UI; deterministic for a given seed.
 * Damage uses [DamageEngine]; Champions status rules (paralysis 1/8, sleep 2–3 counter,
 * freeze thaw by the 3rd turn) are applied. Every log line carries how reliable it is.
 */
object BattleEngine {

    enum class Accuracy(val zh: String) {
        VERIFIED("已驗證機制"), FORMULA("已驗證公式"), ESTIMATED("估算"), UNSUPPORTED("未支援")
    }

    enum class Kind { TURN, ORDER, MOVE, DAMAGE, STATUS, STAT, FAINT, SWITCH, FIELD, INFO, RESULT }

    data class LogEntry(val turn: Int, val kind: Kind, val text: String, val accuracy: Accuracy = Accuracy.VERIFIED)

    enum class RollMode(val zh: String) { RANDOM("隨機"), MIN("最低傷害"), AVERAGE("平均傷害"), MAX("最高傷害") }
    enum class CritMode(val zh: String) { RANDOM("隨機"), NEVER("不擊中要害"), ALWAYS("必定擊中要害") }

    /** Starting conditions for scenario testing (spec §13.2 Mode C). */
    data class Scenario(
        val weather: SpeedEngine.Weather = SpeedEngine.Weather.NONE,
        val terrain: DamageEngine.Terrain = DamageEngine.Terrain.NONE,
        val trickRoom: Boolean = false,
        val tailwind: List<Boolean> = listOf(false, false),
        val reflect: List<Boolean> = listOf(false, false),
        val lightScreen: List<Boolean> = listOf(false, false),
        /** Per side, per brought Pokémon: HP %, status and stat stages. */
        val hpPercent: List<List<Int>> = listOf(emptyList(), emptyList()),
        val status: List<List<Status>> = listOf(emptyList(), emptyList()),
        val boosts: List<List<Map<Stat, Int>>> = listOf(emptyList(), emptyList()),
        val skipSwitchInAbilities: Boolean = false,
    )

    data class Setup(
        val format: BattleFormat,
        /** Brought Pokémon per side, in order: the first 1 (singles) or 2 (doubles) lead. */
        val sides: List<List<TeamSlot>>,
        val names: List<String> = listOf("我方", "對手"),
        val scenario: Scenario = Scenario(),
        val rollMode: RollMode = RollMode.RANDOM,
        val critMode: CritMode = CritMode.RANDOM,
        val maxTurns: Int = 30,
    )

    data class Target(val side: Int, val pos: Int)

    sealed class Action {
        data class UseMove(val moveId: String, val target: Target? = null, val mega: Boolean = false) : Action()
        data class Switch(val benchIndex: Int) : Action()
    }

    // ---- State ----------------------------------------------------------------------------

    class Mon(val side: Int, val index: Int, val slot: TeamSlot, base: Pokemon, val moves: List<Move>, s: Snapshot) {
        var form: Pokemon = base
        var stats: Map<Stat, Int> = TeamEngine.finalStats(s, slot.copy(megaFormId = null))
        var ability: String? = slot.ability ?: base.abilities.firstOrNull()
        var item: String? = slot.item?.let { s.itemById[it]?.name ?: it }
        val maxHp: Int = stats[Stat.HP] ?: 1
        var hp: Int = maxHp
        var status = Status.NONE
        var sleepTurns = 0
        var freezeTurns = 0
        var toxicCounter = 0
        val boosts = mutableMapOf<Stat, Int>()
        var accuracyStage = 0
        var evasionStage = 0
        var isMega = false
        val canMega: Boolean get() = slot.megaFormId != null && !isMega
        var protectCount = 0
        var protectedThisTurn = false
        var usedProtectThisTurn = false
        var flinched = false
        var movedThisTurn = false
        var turnsActive = 0
        var followMe = false
        var helpingHand = false
        var abilityActive = false
        var sashUsed = false
        val name: String = s.displayName(base)
        var displayName: String = name
        val fainted: Boolean get() = hp <= 0
        val hpPercent: Double get() = hp * 100.0 / maxHp
        fun boost(st: Stat) = boosts[st] ?: 0
        fun ab() = ability?.let(::toId)
        fun itemId() = item?.let(::toId)
    }

    class SideState(val mons: List<Mon>, activeCount: Int) {
        val active: IntArray = IntArray(activeCount) { if (it < mons.size) it else -1 }
        var megaUsed = false
        var tailwind = 0
        var reflect = 0
        var lightScreen = 0
        var auroraVeil = 0
        fun activeMons(): List<Mon> = active.filter { it >= 0 }.map { mons[it] }.filter { !it.fainted }
        fun bench(): List<Mon> = mons.filter { m -> !m.fainted && m.index !in active.toList() }
        fun alive(): Boolean = mons.any { !it.fainted }
        fun posOf(m: Mon): Int = active.indexOf(m.index)
    }

    class Battle(val s: Snapshot, val setup: Setup, seed: Long) {
        val te = TypeEngine(s.typeChart, s.types)
        val rng = Random(seed)
        val activeCount = if (setup.format == BattleFormat.DOUBLES) 2 else 1
        val sides: List<SideState>
        var weather = SpeedEngine.Weather.NONE
        var weatherTurns = 0
        var terrain = DamageEngine.Terrain.NONE
        var terrainTurns = 0
        var trickRoom = 0
        var turn = 0
        val log = mutableListOf<LogEntry>()
        var winner: Int? = null
        val over: Boolean get() = winner != null || turn >= setup.maxTurns

        init {
            sides = setup.sides.mapIndexed { side, slots ->
                SideState(slots.filter { it.speciesId in s.pokemonById }.mapIndexed { i, sl ->
                    Mon(side, i, sl, s.pokemonById.getValue(sl.speciesId), sl.moves.mapNotNull { s.moveById[it] }, s)
                }, activeCount)
            }
            val sc = setup.scenario
            weather = sc.weather; weatherTurns = if (sc.weather != SpeedEngine.Weather.NONE) 5 else 0
            terrain = sc.terrain; terrainTurns = if (sc.terrain != DamageEngine.Terrain.NONE) 5 else 0
            trickRoom = if (sc.trickRoom) 5 else 0
            sides.forEachIndexed { i, side ->
                if (sc.tailwind.getOrElse(i) { false }) side.tailwind = 4
                if (sc.reflect.getOrElse(i) { false }) side.reflect = 5
                if (sc.lightScreen.getOrElse(i) { false }) side.lightScreen = 5
                side.mons.forEachIndexed { j, m ->
                    sc.hpPercent.getOrNull(i)?.getOrNull(j)?.let { p -> m.hp = max(1, m.maxHp * p.coerceIn(1, 100) / 100) }
                    sc.status.getOrNull(i)?.getOrNull(j)?.let { st -> if (st != Status.NONE) setStatus(m, st, null, silent = true) }
                    sc.boosts.getOrNull(i)?.getOrNull(j)?.let { b -> b.forEach { (k, v) -> m.boosts[k] = v.coerceIn(-6, 6) } }
                }
            }
            add(Kind.TURN, "對戰開始（${setup.format.zh}）")
            if (!sc.skipSwitchInAbilities) {
                allActive().sortedByDescending { speedOf(it) }.forEach { onSwitchIn(it) }
            }
        }

        fun add(kind: Kind, text: String, acc: Accuracy = Accuracy.VERIFIED) { log += LogEntry(turn, kind, text, acc) }
        fun allActive(): List<Mon> = sides.flatMap { it.activeMons() }
        fun foesOf(m: Mon) = sides[1 - m.side].activeMons()
        fun allyOf(m: Mon) = sides[m.side].activeMons().firstOrNull { it !== m }
        fun label(m: Mon) = "${setup.names[m.side]}的${m.displayName}"

        // ---- Speed and order ------------------------------------------------------------

        fun speedOf(m: Mon): Int {
            val side = sides[m.side]
            val w = if (m.itemId() == "utilityumbrella") SpeedEngine.Weather.NONE else weather
            var v = SpeedEngine.effective(m.stats[Stat.SPE] ?: 1, m.ability, SpeedEngine.Conditions(
                stage = m.boost(Stat.SPE), tailwind = side.tailwind > 0, paralysis = m.status == Status.PARALYSIS,
                choiceScarf = m.itemId() == "choicescarf", ironBall = m.itemId() == "ironball", weather = w,
                abilityActive = m.abilityActive || (m.ab() == "unburden" && m.item == null && m.slot.item != null),
            ))
            if (m.ab() == "surgesurfer" && terrain == DamageEngine.Terrain.ELECTRIC) v *= 2
            return v
        }

        fun priorityOf(m: Mon, move: Move): Int {
            var p = move.priority
            if (move.category == "Status" && m.ab() == "prankster") p += 1
            if (move.id == "grassyglide" && terrain == DamageEngine.Terrain.GRASSY && DamageEngine.isGrounded(sideOf(m))) p += 1
            if (m.ab() == "galewings" && move.type == "Flying" && m.hp == m.maxHp) p += 1
            return p
        }

        fun sideOf(m: Mon, hpExact: Boolean = true) = DamageEngine.Side(
            m.form, m.stats, m.ability, m.item, m.status, m.boosts.toMap(), (m.hp * 100 + m.maxHp - 1) / m.maxHp, 50, m.abilityActive,
            hpExact = if (hpExact) m.hp else null,
        )

        // ---- Turn -------------------------------------------------------------------------

        /** Runs one turn. [choices] maps (side, position) to an action; missing actions → first move. */
        fun runTurn(choices: Map<Pair<Int, Int>, Action>) {
            if (over) return
            turn++
            add(Kind.TURN, "第 $turn 回合")
            val startActive = allActive().toSet()
            allActive().forEach { it.movedThisTurn = false; it.protectedThisTurn = false; it.usedProtectThisTurn = false; it.flinched = false }

            data class Queued(val mon: Mon, val action: Action)
            val queue = mutableListOf<Queued>()
            for ((si, side) in sides.withIndex()) for ((pos, idx) in side.active.withIndex()) {
                if (idx < 0) continue
                val m = side.mons[idx]
                if (m.fainted) continue
                queue += Queued(m, choices[si to pos] ?: defaultAction(m))
            }
            // 1) Switches (fastest first)
            queue.filter { it.action is Action.Switch }.sortedByDescending { speedOf(it.mon) }.forEach { q ->
                switchIn(q.mon, (q.action as Action.Switch).benchIndex)
            }
            // 2) Mega Evolution (before moves), one per side per battle
            queue.filter { (it.action as? Action.UseMove)?.mega == true }.sortedByDescending { speedOf(it.mon) }.forEach { q ->
                megaEvolve(q.mon)
            }
            // 3) Moves: priority, then speed (Trick Room reverses), random tie-break
            val moves = queue.filter { it.action is Action.UseMove && !it.mon.fainted && sides[it.mon.side].posOf(it.mon) >= 0 }
                .map { q -> Triple(q, moveFor(q.mon, (q.action as Action.UseMove).moveId), rng.nextDouble()) }
            val ordered = moves.sortedWith(compareByDescending<Triple<Queued, Move, Double>> { priorityOf(it.first.mon, it.second) }
                .thenByDescending { t -> val sp = speedOf(t.first.mon); if (trickRoom > 0) -sp else sp }
                .thenBy { it.third })
            add(Kind.ORDER, "行動次序：" + ordered.joinToString(" → ") { "${it.first.mon.displayName}（${speedOf(it.first.mon)}）" } +
                if (trickRoom > 0) "（戲法空間）" else "")
            for ((q, move, _) in ordered) {
                if (winner != null) break
                if (q.mon.fainted || sides[q.mon.side].posOf(q.mon) < 0) continue
                executeMove(q.mon, move, (q.action as Action.UseMove).target)
                q.mon.movedThisTurn = true
                checkWinner()
            }
            if (winner == null) endOfTurn()
            allActive().forEach { m ->
                if (!m.usedProtectThisTurn) m.protectCount = 0
                m.followMe = false; m.helpingHand = false
                if (m in startActive) m.turnsActive++   // a Pokémon switched in mid-turn can still Fake Out next turn
            }
            if (winner == null) replaceFainted()
            checkWinner()
            if (winner == null && turn >= setup.maxTurns) add(Kind.RESULT, "到達回合上限（${setup.maxTurns}），未分勝負", Accuracy.ESTIMATED)
        }

        fun defaultAction(m: Mon): Action = Action.UseMove(m.moves.firstOrNull()?.id ?: "struggle")

        private fun moveFor(m: Mon, id: String): Move = m.moves.firstOrNull { it.id == id } ?: m.moves.firstOrNull()
            ?: Move(id = "struggle", name = "Struggle", nameZh = "掙扎", type = "???", category = "Physical", basePower = 50, priority = 0)

        // ---- Switching / Mega -------------------------------------------------------------

        fun switchIn(out: Mon, benchIndex: Int) {
            val side = sides[out.side]
            val pos = side.posOf(out)
            val incoming = side.mons.getOrNull(benchIndex)
            if (pos < 0 || incoming == null || incoming.fainted || incoming.index in side.active.toList()) return
            if (!out.fainted) {
                if (out.ab() == "regenerator") out.hp = min(out.maxHp, out.hp + out.maxHp / 3)
                if (out.ab() == "naturalcure") out.status = Status.NONE
                add(Kind.SWITCH, "${label(out)} 退下")
            }
            out.boosts.clear(); out.accuracyStage = 0; out.evasionStage = 0; out.protectCount = 0; out.abilityActive = false
            if (out.status == Status.TOXIC) out.toxicCounter = 0
            side.active[pos] = incoming.index
            incoming.turnsActive = 0
            add(Kind.SWITCH, "${label(incoming)} 出場（HP ${"%.0f".format(incoming.hpPercent)}%）")
            onSwitchIn(incoming)
        }

        private fun onSwitchIn(m: Mon) {
            m.turnsActive = 0
            when (m.ab()) {
                "intimidate" -> foesOf(m).forEach { f ->
                    when (f.ab()) {
                        "clearbody", "hypercutter", "innerfocus", "oblivious", "owntempo", "scrappy", "whitesmoke", "fullmetalbody", "guarddog" ->
                            add(Kind.STAT, "${label(f)} 的特性 ${f.ability} 令威嚇無效")
                        else -> {
                            changeStat(f, Stat.ATK, -1, "威嚇")
                            if (f.ab() == "defiant") changeStat(f, Stat.ATK, 2, "不服輸")
                            if (f.ab() == "competitive") changeStat(f, Stat.SPA, 2, "好勝")
                        }
                    }
                }
                "drought" -> setWeather(SpeedEngine.Weather.SUN, m, "日照")
                "drizzle" -> setWeather(SpeedEngine.Weather.RAIN, m, "降雨")
                "sandstream" -> setWeather(SpeedEngine.Weather.SAND, m, "揚沙")
                "snowwarning" -> setWeather(SpeedEngine.Weather.SNOW, m, "降雪")
                "electricsurge", "hadronengine" -> setTerrain(DamageEngine.Terrain.ELECTRIC, m)
                "grassysurge" -> setTerrain(DamageEngine.Terrain.GRASSY, m)
                "psychicsurge" -> setTerrain(DamageEngine.Terrain.PSYCHIC, m)
                "mistysurge" -> setTerrain(DamageEngine.Terrain.MISTY, m)
                "commander" -> add(Kind.INFO, "${label(m)} 的特性 Commander 未模擬", Accuracy.UNSUPPORTED)
            }
        }

        fun megaEvolve(m: Mon) {
            val side = sides[m.side]
            if (side.megaUsed || !m.canMega || m.fainted) return
            val megaId = m.slot.megaFormId ?: return
            val mega = s.pokemonById[megaId] ?: return
            if (m.itemId() != mega.megaStone?.let(::toId)) { add(Kind.INFO, "${label(m)} 沒有持有 ${mega.megaStone}，不能 Mega Evolution"); return }
            m.form = mega
            m.stats = TeamEngine.finalStats(s, m.slot).toMutableMap().also { it[Stat.HP] = m.maxHp }
            m.ability = mega.abilities.firstOrNull()
            m.isMega = true
            m.displayName = s.displayName(mega)
            side.megaUsed = true
            add(Kind.INFO, "${setup.names[m.side]}的${m.name} Mega Evolution 成為 ${m.displayName}（特性 ${m.ability}）")
            // Mega Evolution happens before moves; the new Speed is used for this turn's order.
            add(Kind.INFO, "Mega Evolution 後以新速度排序（第 7 世代起規則）", Accuracy.ESTIMATED)
        }

        private fun replaceFainted() {
            for (side in sides) for (pos in side.active.indices) {
                val idx = side.active[pos]
                if (idx >= 0 && side.mons[idx].fainted) {
                    val next = side.bench().firstOrNull()
                    if (next == null) side.active[pos] = -1
                    else {
                        side.active[pos] = next.index
                        next.boosts.clear(); next.turnsActive = 0
                        add(Kind.SWITCH, "${label(next)} 出場（補位：自動選擇第一隻後備）", Accuracy.ESTIMATED)
                        onSwitchIn(next)
                    }
                }
            }
        }

        private fun checkWinner() {
            if (winner != null) return
            val alive = sides.map { it.alive() }
            winner = when {
                !alive[0] && !alive[1] -> -1
                !alive[0] -> 1
                !alive[1] -> 0
                else -> null
            }
            winner?.let { w -> add(Kind.RESULT, if (w < 0) "雙方同時全部倒下：和局" else "${setup.names[w]}勝出（第 $turn 回合）") }
        }

        // ---- Field changes ----------------------------------------------------------------

        private fun setWeather(w: SpeedEngine.Weather, by: Mon, what: String) {
            if (weather == w) return
            val rock = mapOf(SpeedEngine.Weather.SUN to "heatrock", SpeedEngine.Weather.RAIN to "damprock",
                SpeedEngine.Weather.SAND to "smoothrock", SpeedEngine.Weather.SNOW to "icyrock")[w]
            weather = w; weatherTurns = if (by.itemId() == rock) 8 else 5
            add(Kind.FIELD, "${label(by)} 令天氣變為${w.zh}（${weatherTurns} 回合）")
        }

        private fun setTerrain(t: DamageEngine.Terrain, by: Mon) {
            if (terrain == t) return
            terrain = t; terrainTurns = if (by.itemId() == "terrainextender") 8 else 5
            add(Kind.FIELD, "${label(by)} 展開了${t.zh}（${terrainTurns} 回合）")
        }

        // ---- Stats and status -------------------------------------------------------------

        fun changeStat(m: Mon, st: Stat, delta: Int, source: String) {
            if (m.fainted) return
            var d = delta
            if (m.ab() == "contrary") d = -d
            if (m.ab() == "simple") d *= 2
            if (d < 0 && m.ab() in setOf("clearbody", "whitesmoke", "fullmetalbody") && source != "自身") {
                add(Kind.STAT, "${label(m)} 的特性 ${m.ability} 防止能力下降"); return
            }
            val before = m.boost(st)
            val after = (before + d).coerceIn(-6, 6)
            if (after == before) { add(Kind.STAT, "${label(m)} 的${st.zh}無法再${if (d > 0) "提升" else "下降"}"); return }
            m.boosts[st] = after
            add(Kind.STAT, "${label(m)} 的${st.zh}${if (after > before) "提升" else "下降"} ${kotlin.math.abs(after - before)} 級（$source）→ ${if (after > 0) "+" else ""}$after")
        }

        fun canHaveStatus(m: Mon, st: Status, source: Move?): String? {
            if (m.status != Status.NONE) return "已經有異常狀態"
            val grounded = DamageEngine.isGrounded(sideOf(m))
            if (terrain == DamageEngine.Terrain.MISTY && grounded) return "薄霧場地保護"
            if (st == Status.SLEEP && terrain == DamageEngine.Terrain.ELECTRIC && grounded) return "電氣場地保護"
            if (source != null && "powder" in source.flags && ("Grass" in m.form.types || m.ab() == "overcoat" || m.itemId() == "safetygoggles")) return "不受粉末影響"
            if (m.ab() == "purifyingsalt" || m.ab() == "comatose") return "特性 ${m.ability}"
            return when (st) {
                Status.BURN -> if ("Fire" in m.form.types) "火屬性不會灼傷" else if (m.ab() in setOf("waterveil", "waterbubble", "thermalexchange")) "特性 ${m.ability}" else null
                Status.PARALYSIS -> if ("Electric" in m.form.types) "電屬性不會麻痺" else if (m.ab() == "limber") "特性 Limber" else null
                Status.POISON, Status.TOXIC -> if ("Poison" in m.form.types || "Steel" in m.form.types) "毒／鋼屬性不會中毒" else if (m.ab() == "immunity") "特性 Immunity" else null
                Status.SLEEP -> if (m.ab() in setOf("insomnia", "vitalspirit", "sweetveil")) "特性 ${m.ability}" else null
                Status.FREEZE -> if ("Ice" in m.form.types || weather == SpeedEngine.Weather.SUN) "不會冰凍" else null
                Status.NONE -> null
            }
        }

        fun setStatus(m: Mon, st: Status, source: Move?, silent: Boolean = false): Boolean {
            if (!silent) canHaveStatus(m, st, source)?.let { why -> if (source?.category == "Status") add(Kind.STATUS, "${label(m)} 沒有受到影響（$why）"); return false }
            m.status = st
            when (st) {
                Status.SLEEP -> m.sleepTurns = listOf(2, 3, 3)[rng.nextInt(3)]
                Status.FREEZE -> m.freezeTurns = 3
                Status.TOXIC -> m.toxicCounter = 0
                else -> {}
            }
            if (!silent) add(Kind.STATUS, "${label(m)} ${statusText(st)}")
            return true
        }

        private fun statusText(st: Status) = when (st) {
            Status.BURN -> "被灼傷了"; Status.PARALYSIS -> "麻痺了"; Status.POISON -> "中毒了"; Status.TOXIC -> "中了劇毒"
            Status.SLEEP -> "睡著了"; Status.FREEZE -> "被冰凍了"; Status.NONE -> ""
        }

        /** Champions status checks before a move. Returns false if the Pokémon can't act. */
        private fun canAct(m: Mon, move: Move): Boolean {
            when (m.status) {
                Status.SLEEP -> {
                    m.sleepTurns--
                    if (m.sleepTurns <= 0) { m.status = Status.NONE; add(Kind.STATUS, "${label(m)} 醒來了") }
                    else if (move.id != "sleeptalk" && move.id != "snore") { add(Kind.STATUS, "${label(m)} 正在睡覺"); return false }
                }
                Status.FREEZE -> {
                    m.freezeTurns--
                    if ("defrost" in move.flags || m.freezeTurns <= 0 || rng.nextInt(4) == 0) { m.status = Status.NONE; add(Kind.STATUS, "${label(m)} 解凍了") }
                    else { add(Kind.STATUS, "${label(m)} 被冰凍，不能行動"); return false }
                }
                Status.PARALYSIS -> if (rng.nextInt(8) == 0) { add(Kind.STATUS, "${label(m)} 因麻痺而不能行動（1/8）"); return false }
                else -> {}
            }
            if (m.flinched) { add(Kind.STATUS, "${label(m)} 畏縮了"); return false }
            return true
        }

        // ---- Moves ------------------------------------------------------------------------

        private fun resolveTargets(user: Mon, move: Move, chosen: Target?): List<Mon> {
            val foes = foesOf(user)
            val ally = allyOf(user)
            return when (move.target) {
                "self", "allySide", "all", "foeSide", "allyTeam" -> emptyList()
                "adjacentAlly" -> listOfNotNull(ally)
                "allAdjacentFoes" -> foes
                "allAdjacent" -> foes + listOfNotNull(ally)
                "adjacentAllyOrSelf" -> listOf(ally ?: user)
                else -> {
                    // Redirection (Follow Me / Rage Powder) for single-target moves from the other side.
                    val redirect = foes.firstOrNull { it.followMe }
                    val stalwart = user.ab() in setOf("stalwart", "propellertail")
                    val pick = chosen?.let { t -> sides.getOrNull(t.side)?.active?.getOrNull(t.pos)?.takeIf { it >= 0 }?.let { sides[t.side].mons[it] } }
                    when {
                        redirect != null && !stalwart && (pick == null || pick.side != user.side) -> listOf(redirect)
                        pick != null && !pick.fainted -> listOf(pick)
                        else -> listOfNotNull(foes.firstOrNull())
                    }
                }
            }
        }

        private fun hits(user: Mon, target: Mon, move: Move): Boolean {
            val accEl = move.accuracy as? JsonPrimitive ?: return true
            if (accEl.booleanOrNull == true) return true
            var acc = accEl.intOrNull ?: return true
            if (user.ab() == "noguard" || target.ab() == "noguard") return true
            if (move.id in setOf("thunder", "hurricane")) {
                if (weather == SpeedEngine.Weather.RAIN) return true
                if (weather == SpeedEngine.Weather.SUN) acc = 50
            }
            if (move.id == "blizzard" && weather == SpeedEngine.Weather.SNOW) return true
            val stage = (user.accuracyStage - target.evasionStage).coerceIn(-6, 6)
            var a = acc.toDouble() * (if (stage >= 0) (3 + stage) / 3.0 else 3.0 / (3 - stage))
            if (user.ab() == "compoundeyes") a *= 1.3
            if (user.ab() == "hustle" && move.category == "Physical") a *= 0.8
            return rng.nextDouble() * 100 < a
        }

        private fun critRoll(user: Mon, target: Mon, move: Move): Boolean {
            if (target.ab() in setOf("battlearmor", "shellarmor")) return false
            return when (setup.critMode) {
                CritMode.NEVER -> move.willCrit == true
                CritMode.ALWAYS -> true
                CritMode.RANDOM -> {
                    if (move.willCrit == true) return true
                    var stage = move.critRatio - 1
                    if (user.ab() == "superluck") stage++
                    if (user.itemId() in setOf("scopelens", "razorclaw")) stage++
                    val chance = when (stage) { 0 -> 1.0 / 24; 1 -> 1.0 / 8; 2 -> 0.5; else -> 1.0 }
                    rng.nextDouble() < chance
                }
            }
        }

        private fun rollIndex(): Int = when (setup.rollMode) {
            RollMode.RANDOM -> rng.nextInt(16); RollMode.MIN -> 0; RollMode.AVERAGE -> 7; RollMode.MAX -> 15
        }

        fun executeMove(user: Mon, move: Move, chosen: Target?) {
            if (!canAct(user, move)) return
            val mName = move.nameZh ?: move.name
            add(Kind.MOVE, "${label(user)} 使用了 $mName")
            if ((move.id == "fakeout" || move.id == "firstimpression") && user.turnsActive > 0) {
                add(Kind.MOVE, "但是失敗了（只可在出場後第一回合使用）"); return
            }
            // Protect-type moves
            if (move.stallingMove == true || move.volatileStatus == "protect") {
                val chance = 1.0 / Math.pow(3.0, user.protectCount.toDouble())
                user.usedProtectThisTurn = true
                if (allActive().all { it.movedThisTurn || it === user }) {
                    add(Kind.MOVE, "但是失敗了（最後行動）"); user.protectCount = 0; return
                }
                if (rng.nextDouble() < chance) { user.protectedThisTurn = true; user.protectCount++; add(Kind.MOVE, "${label(user)} 守住了自己") }
                else { add(Kind.MOVE, "但是失敗了（連續使用）"); user.protectCount = 0 }
                return
            }
            if (move.category == "Status") { statusMove(user, move, chosen); return }

            val targets = resolveTargets(user, move, chosen)
            if (targets.isEmpty()) { add(Kind.MOVE, "但沒有目標"); return }
            val spread = targets.size > 1
            var totalDealt = 0
            var anyHit = false
            for (t in targets) {
                if (t.fainted) continue
                if (t.protectedThisTurn && "protect" in move.flags) { add(Kind.MOVE, "${label(t)} 守住了攻擊"); continue }
                if (!hits(user, t, move)) { add(Kind.MOVE, "沒有命中 ${label(t)}"); continue }
                val tSide = sides[t.side]
                val crit = critRoll(user, t, move)
                val field = DamageEngine.Field(
                    gameType = if (activeCount == 2) DamageEngine.GameType.DOUBLES else DamageEngine.GameType.SINGLES,
                    weather = weather, terrain = terrain,
                    reflect = tSide.reflect > 0, lightScreen = tSide.lightScreen > 0, auroraVeil = tSide.auroraVeil > 0,
                    helpingHand = user.helpingHand, friendGuard = allyOf(t)?.ab() == "friendguard",
                    spreadHit = spread, critical = crit,
                )
                val r = DamageEngine.calculate(s, sideOf(user), sideOf(t), move, field)
                if (r.effectiveness == 0.0 || r.max == 0) {
                    add(Kind.DAMAGE, "對 ${label(t)} 沒有效果" + (r.notes.firstOrNull()?.let { "（$it）" } ?: "")); continue
                }
                var dmg = r.perHitRolls.sumOf { hit -> hit[rollIndex()] }
                val full = t.hp == t.maxHp
                if (dmg >= t.hp && full && (t.ab() == "sturdy" || (t.itemId() == "focussash" && !t.sashUsed))) {
                    dmg = t.hp - 1
                    if (t.itemId() == "focussash") { t.sashUsed = true; t.item = null }
                    add(Kind.DAMAGE, "${label(t)} 撐住了（${if (t.ab() == "sturdy") "結實" else "氣勢披帶"}）")
                }
                dmg = min(dmg, t.hp)
                t.hp -= dmg
                totalDealt += dmg
                anyHit = true
                val effText = when { r.effectiveness > 1 -> "效果絕佳！"; r.effectiveness < 1 -> "效果不好…"; else -> "" }
                add(Kind.DAMAGE, "${if (crit) "擊中要害！" else ""}$effText${label(t)} 受到 $dmg 傷害（${"%.1f".format(dmg * 100.0 / t.maxHp)}%），剩餘 ${"%.0f".format(t.hpPercent)}%",
                    if (r.verification == Verification.VERIFIED) Accuracy.FORMULA else Accuracy.ESTIMATED)
                if (r.notes.any { "抗性樹果" in it }) t.item = null
                if (move.id == "knockoff" && t.item != null && s.itemById[t.itemId() ?: ""]?.isMegaStone != true && !t.fainted) {
                    add(Kind.INFO, "${label(t)} 的 ${t.item} 被拍落了"); t.item = null
                }
                // Contact damage
                if ("contact" in move.flags && user.itemId() != "protectivepads") {
                    if (t.ab() in setOf("roughskin", "ironbarbs")) hurt(user, user.maxHp / 8, "${t.ability}")
                    if (t.itemId() == "rockyhelmet") hurt(user, user.maxHp / 6, "凸凸頭盔")
                }
                if (t.fainted) { add(Kind.FAINT, "${label(t)} 倒下了"); continue }
                // Weakness Policy, Sitrus Berry
                if (r.effectiveness > 1 && t.itemId() == "weaknesspolicy") { t.item = null; changeStat(t, Stat.ATK, 2, "弱點保險"); changeStat(t, Stat.SPA, 2, "弱點保險") }
                if (t.itemId() == "sitrusberry" && t.hp * 2 <= t.maxHp) { t.item = null; heal(t, t.maxHp / 4, "文柚果") }
                // Secondary effects
                if (user.ab() != "sheerforce" && t.ab() != "shielddust" && t.itemId() != "covertcloak") applySecondaries(user, t, move)
            }
            if (!anyHit) return
            move.self.asObj()?.get("boosts").asBoosts().forEach { (st, d) -> changeStat(user, st, d, "自身") }
            move.drain.asFraction()?.let { (n, d) -> if (totalDealt > 0) heal(user, max(1, totalDealt * n / d), "吸取") }
            move.recoil.asFraction()?.let { (n, d) -> if (user.ab() != "rockhead" && totalDealt > 0) hurt(user, max(1, totalDealt * n / d), "反作用力") }
            if (user.itemId() == "lifeorb" && totalDealt > 0 && !(user.ab() == "sheerforce" && move.secondary != null)) hurt(user, user.maxHp / 10, "生命寶珠")
            if (move.selfdestruct != null) { user.hp = 0; add(Kind.FAINT, "${label(user)} 倒下了") }
            if (user.fainted) return
            if (move.selfSwitch != null) selfSwitch(user)
        }

        private fun applySecondaries(user: Mon, t: Mon, move: Move) {
            val list = buildList {
                move.secondary.asObj()?.let { add(it) }
                (move.secondaries as? JsonArray)?.forEach { e -> e.asObj()?.let { add(it) } }
            }
            for (sec in list) {
                var chance = (sec["chance"] as? JsonPrimitive)?.intOrNull ?: 100
                if (user.ab() == "serenegrace") chance = min(100, chance * 2)
                if (rng.nextInt(100) >= chance) continue
                sec["boosts"].asBoosts().forEach { (st, d) -> changeStat(t, st, d, "${move.nameZh ?: move.name} 的追加效果") }
                (sec["status"] as? JsonPrimitive)?.content?.let { code -> statusOf(code)?.let { st -> if (canHaveStatus(t, st, move) == null) setStatus(t, st, move) } }
                if ((sec["volatileStatus"] as? JsonPrimitive)?.content == "flinch" && !t.movedThisTurn && t.ab() != "innerfocus") t.flinched = true
                sec["self"].asObj()?.get("boosts").asBoosts().forEach { (st, d) -> changeStat(user, st, d, "自身") }
            }
        }

        private fun statusMove(user: Mon, move: Move, chosen: Target?) {
            var handled = false
            // Field
            move.weather?.let { w ->
                handled = true
                when (w) { "sunnyday" -> setWeather(SpeedEngine.Weather.SUN, user, ""); "raindance" -> setWeather(SpeedEngine.Weather.RAIN, user, "")
                    "sandstorm" -> setWeather(SpeedEngine.Weather.SAND, user, ""); "snowscape", "hail" -> setWeather(SpeedEngine.Weather.SNOW, user, "") }
            }
            move.terrain?.let { t ->
                handled = true
                when (t) { "electricterrain" -> setTerrain(DamageEngine.Terrain.ELECTRIC, user); "grassyterrain" -> setTerrain(DamageEngine.Terrain.GRASSY, user)
                    "psychicterrain" -> setTerrain(DamageEngine.Terrain.PSYCHIC, user); "mistyterrain" -> setTerrain(DamageEngine.Terrain.MISTY, user) }
            }
            if (move.pseudoWeather == "trickroom") {
                handled = true
                if (trickRoom > 0) { trickRoom = 0; add(Kind.FIELD, "扭曲的空間回復原狀了") } else { trickRoom = 5; add(Kind.FIELD, "${label(user)} 扭曲了時空（戲法空間 5 回合）") }
            }
            move.sideCondition?.let { c ->
                val side = sides[user.side]
                handled = true
                when (c) {
                    "tailwind" -> { side.tailwind = 4; add(Kind.FIELD, "${setup.names[user.side]}吹起了順風（4 回合）") }
                    "reflect" -> { side.reflect = if (user.itemId() == "lightclay") 8 else 5; add(Kind.FIELD, "${setup.names[user.side]}展開了反射壁") }
                    "lightscreen" -> { side.lightScreen = if (user.itemId() == "lightclay") 8 else 5; add(Kind.FIELD, "${setup.names[user.side]}展開了光牆") }
                    "auroraveil" -> if (weather == SpeedEngine.Weather.SNOW) { side.auroraVeil = if (user.itemId() == "lightclay") 8 else 5; add(Kind.FIELD, "${setup.names[user.side]}展開了極光幕") }
                        else add(Kind.MOVE, "但是失敗了（需要下雪）")
                    else -> { handled = false }
                }
            }
            when (move.volatileStatus) {
                "followme", "ragepowder" -> { handled = true; if (activeCount == 2) { user.followMe = true; add(Kind.MOVE, "${label(user)} 成為了注目的焦點") } else add(Kind.MOVE, "但是沒有效果（單打）") }
                "helpinghand" -> { handled = true; allyOf(user)?.let { it.helpingHand = true; add(Kind.MOVE, "${label(user)} 準備幫助 ${label(it)}") } ?: add(Kind.MOVE, "但是失敗了") }
            }
            // Self boosts / heal
            if (move.target == "self" || move.target == "adjacentAllyOrSelf" && move.boosts != null && allyOf(user) == null) {
                move.boosts?.forEach { (k, v) -> statOf(k)?.let { changeStat(user, it, v, move.nameZh ?: move.name); handled = true } }
            }
            move.heal?.let { h ->
                handled = true
                var frac = h[0].toDouble() / h[1]
                if (move.id in setOf("moonlight", "synthesis", "morningsun")) frac = when (weather) { SpeedEngine.Weather.SUN -> 2.0 / 3; SpeedEngine.Weather.NONE -> 0.5; else -> 0.25 }
                if (move.id == "shoreup" && weather == SpeedEngine.Weather.SAND) frac = 2.0 / 3
                heal(user, (user.maxHp * frac).toInt(), move.nameZh ?: move.name)
            }
            // Effects on targets
            val targets = if (move.target in setOf("normal", "any", "adjacentFoe", "allAdjacentFoes", "allAdjacent", "adjacentAlly", "randomNormal")) resolveTargets(user, move, chosen) else emptyList()
            for (t in targets) {
                if (t.fainted) continue
                if (t.side != user.side && t.protectedThisTurn && "protect" in move.flags) { add(Kind.MOVE, "${label(t)} 守住了"); continue }
                if (t.side != user.side && user.ab() == "prankster" && "Dark" in t.form.types) { add(Kind.MOVE, "對 ${label(t)} 沒有效果（惡屬性不受惡作劇之心影響）"); continue }
                if (!hits(user, t, move)) { add(Kind.MOVE, "沒有命中 ${label(t)}"); continue }
                move.status?.let { code ->
                    handled = true
                    statusOf(code)?.let { st ->
                        if (move.type == "Electric" && te.multiplier("Electric", t.form.types) == 0.0) add(Kind.STATUS, "對 ${label(t)} 沒有效果")
                        else setStatus(t, st, move)
                    }
                }
                move.boosts?.takeIf { move.target != "self" }?.forEach { (k, v) -> statOf(k)?.let { changeStat(t, it, v, move.nameZh ?: move.name); handled = true } }
                if (move.id == "partingshot") { handled = true; changeStat(t, Stat.ATK, -1, "抛下狠話"); changeStat(t, Stat.SPA, -1, "抛下狠話") }
            }
            if (move.id == "partingshot" && !user.fainted) selfSwitch(user)
            if (!handled) add(Kind.INFO, "${move.nameZh ?: move.name} 的效果未模擬", Accuracy.UNSUPPORTED)
        }

        private fun selfSwitch(user: Mon) {
            val next = sides[user.side].bench().firstOrNull() ?: return
            add(Kind.SWITCH, "${label(user)} 回到後備（自動換入第一隻後備）", Accuracy.ESTIMATED)
            switchIn(user, next.index)
        }

        private fun hurt(m: Mon, amount: Int, why: String) {
            if (m.fainted || amount <= 0 || m.ab() == "magicguard") return
            val d = min(amount, m.hp)
            m.hp -= d
            add(Kind.DAMAGE, "${label(m)} 因$why 受到 $d 傷害（剩餘 ${"%.0f".format(m.hpPercent)}%）")
            if (m.fainted) add(Kind.FAINT, "${label(m)} 倒下了")
        }

        private fun heal(m: Mon, amount: Int, why: String) {
            if (m.fainted || m.hp >= m.maxHp || amount <= 0) return
            val h = min(amount, m.maxHp - m.hp)
            m.hp += h
            add(Kind.DAMAGE, "${label(m)} 因$why 回復 $h HP（${"%.0f".format(m.hpPercent)}%）")
        }

        // ---- End of turn ------------------------------------------------------------------

        private fun endOfTurn() {
            val act = allActive().sortedByDescending { speedOf(it) }
            if (weather == SpeedEngine.Weather.SAND) act.forEach { m ->
                if (m.form.types.none { it in setOf("Rock", "Ground", "Steel") } && m.ab() !in setOf("sandveil", "sandrush", "sandforce", "overcoat") && m.itemId() != "safetygoggles")
                    hurt(m, max(1, m.maxHp / 16), "沙暴")
            }
            if (terrain == DamageEngine.Terrain.GRASSY) act.forEach { m -> if (DamageEngine.isGrounded(sideOf(m))) heal(m, max(1, m.maxHp / 16), "青草場地") }
            act.forEach { m ->
                when (m.itemId()) {
                    "leftovers" -> heal(m, max(1, m.maxHp / 16), "吃剩的東西")
                    "blacksludge" -> if ("Poison" in m.form.types) heal(m, max(1, m.maxHp / 16), "黑色污泥") else hurt(m, max(1, m.maxHp / 8), "黑色污泥")
                }
            }
            act.forEach { m ->
                if (m.fainted) return@forEach
                when (m.status) {
                    Status.BURN -> hurt(m, max(1, m.maxHp / 16), "灼傷")
                    Status.POISON -> if (m.ab() == "poisonheal") heal(m, max(1, m.maxHp / 8), "毒療") else hurt(m, max(1, m.maxHp / 8), "中毒")
                    Status.TOXIC -> { m.toxicCounter = min(15, m.toxicCounter + 1); if (m.ab() == "poisonheal") heal(m, max(1, m.maxHp / 8), "毒療") else hurt(m, max(1, m.maxHp * m.toxicCounter / 16), "劇毒") }
                    else -> {}
                }
                if (m.ab() == "speedboost" && m.turnsActive > 0 && !m.fainted) changeStat(m, Stat.SPE, 1, "加速")
            }
            if (weatherTurns > 0 && --weatherTurns == 0) { add(Kind.FIELD, "${weather.zh}結束了"); weather = SpeedEngine.Weather.NONE }
            if (terrainTurns > 0 && --terrainTurns == 0) { add(Kind.FIELD, "${terrain.zh}消失了"); terrain = DamageEngine.Terrain.NONE }
            if (trickRoom > 0 && --trickRoom == 0) add(Kind.FIELD, "戲法空間結束了")
            sides.forEachIndexed { i, side ->
                if (side.tailwind > 0 && --side.tailwind == 0) add(Kind.FIELD, "${setup.names[i]}的順風停止了")
                if (side.reflect > 0 && --side.reflect == 0) add(Kind.FIELD, "${setup.names[i]}的反射壁消失了")
                if (side.lightScreen > 0 && --side.lightScreen == 0) add(Kind.FIELD, "${setup.names[i]}的光牆消失了")
                if (side.auroraVeil > 0 && --side.auroraVeil == 0) add(Kind.FIELD, "${setup.names[i]}的極光幕消失了")
            }
            checkWinner()
        }
    }

    // ---- JSON helpers -----------------------------------------------------------------------

    private fun JsonElement?.asObj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.asBoosts(): List<Pair<Stat, Int>> =
        (this as? JsonObject)?.mapNotNull { (k, v) -> statOf(k)?.let { st -> (v as? JsonPrimitive)?.intOrNull?.let { st to it } } }.orEmpty()
    private fun JsonElement?.asFraction(): Pair<Int, Int>? = (this as? JsonArray)?.let { a ->
        val n = (a.getOrNull(0) as? JsonPrimitive)?.intOrNull; val d = (a.getOrNull(1) as? JsonPrimitive)?.intOrNull
        if (n != null && d != null && d > 0) n to d else null
    }
    fun statOf(k: String): Stat? = when (k) { "atk" -> Stat.ATK; "def" -> Stat.DEF; "spa" -> Stat.SPA; "spd" -> Stat.SPD; "spe" -> Stat.SPE; else -> null }
    fun statusOf(code: String): Status? = when (code) {
        "brn" -> Status.BURN; "par" -> Status.PARALYSIS; "psn" -> Status.POISON; "tox" -> Status.TOXIC; "slp" -> Status.SLEEP; "frz" -> Status.FREEZE; else -> null
    }

    // ---- Simple AI (explainable): highest expected damage --------------------------------

    /** Picks, for each active Pokémon of [side], the move and target with the highest expected damage. */
    fun aiChoices(b: Battle, side: Int, allowMega: Boolean = true): Map<Pair<Int, Int>, Action> {
        val out = mutableMapOf<Pair<Int, Int>, Action>()
        var megaPlanned = b.sides[side].megaUsed || !allowMega
        for ((pos, idx) in b.sides[side].active.withIndex()) {
            if (idx < 0) continue
            val m = b.sides[side].mons[idx]
            if (m.fainted) continue
            val mega = !megaPlanned && m.canMega
            if (mega) megaPlanned = true
            var best: Pair<Double, Action>? = null
            for (mv in m.moves) {
                if (mv.category == "Status") continue
                if ((mv.id == "fakeout" || mv.id == "firstimpression") && m.turnsActive > 0) continue
                for (f in b.foesOf(m)) {
                    val fPos = b.sides[f.side].posOf(f)
                    val field = DamageEngine.Field(gameType = if (b.activeCount == 2) DamageEngine.GameType.DOUBLES else DamageEngine.GameType.SINGLES,
                        weather = b.weather, terrain = b.terrain, spreadHit = mv.target in setOf("allAdjacentFoes", "allAdjacent") && b.foesOf(m).size > 1)
                    val r = DamageEngine.calculate(b.s, b.sideOf(m), b.sideOf(f), mv, field)
                    val avg = r.rolls.average()
                    val acc = ((mv.accuracy as? JsonPrimitive)?.intOrNull ?: 100) / 100.0
                    var score = min(1.0, avg / max(1, f.hp)) * acc
                    if (r.min >= f.hp) score += 0.5
                    if (mv.target == "allAdjacentFoes") score *= 1.0 + 0.5 * (b.foesOf(m).size - 1)
                    if (mv.target == "allAdjacent") b.allyOf(m)?.let { ally ->
                        val ar = DamageEngine.calculate(b.s, b.sideOf(m), b.sideOf(ally), mv, field.copy(spreadHit = true))
                        score -= min(1.0, ar.rolls.average() / max(1, ally.hp))
                    }
                    if (mv.id == "fakeout") score += 0.3
                    if (best == null || score > best.first) best = score to Action.UseMove(mv.id, Target(f.side, fPos), mega)
                }
            }
            out[side to pos] = best?.second ?: Action.UseMove(m.moves.firstOrNull()?.id ?: "struggle", b.foesOf(m).firstOrNull()?.let { Target(it.side, b.sides[it.side].posOf(it)) }, mega)
        }
        return out
    }

    // ---- Monte Carlo (spec §13.2 Mode B / C) -------------------------------------------------

    data class TurnStats(val runs: Int, val faintChance: Map<String, Double>, val avgHpLeft: Map<String, Double>, val firstMover: Map<String, Double>)
    data class BattleStats(val runs: Int, val winRate: List<Double>, val drawRate: Double, val avgTurns: Double)

    /** Runs the first turn [runs] times with fixed choices and different random seeds. */
    fun simulateTurn(s: Snapshot, setup: Setup, choices: Map<Pair<Int, Int>, Action>, runs: Int = 200): TurnStats {
        val faint = HashMap<String, Int>(); val hpLeft = HashMap<String, Double>(); val first = HashMap<String, Int>()
        repeat(runs) { i ->
            val b = Battle(s, setup, seed = 1000L + i)
            b.runTurn(choices)
            b.log.firstOrNull { it.kind == Kind.MOVE }?.text?.substringBefore(" 使用了")?.let { first.merge(it, 1, Int::plus) }
            for (side in b.sides) for (m in side.mons) {
                val key = "${b.setup.names[m.side]}的${m.name}"
                if (m.fainted) faint.merge(key, 1, Int::plus)
                hpLeft.merge(key, m.hpPercent, Double::plus)
            }
        }
        return TurnStats(runs, faint.mapValues { it.value.toDouble() / runs }, hpLeft.mapValues { it.value / runs }, first.mapValues { it.value.toDouble() / runs })
    }

    /** Full battles with the simple AI on both sides. Result depends on the AI: an estimate only. */
    fun simulateBattles(s: Snapshot, setup: Setup, runs: Int = 100): BattleStats {
        val wins = IntArray(2); var draws = 0; var turns = 0
        repeat(runs) { i ->
            val b = Battle(s, setup, seed = 5000L + i)
            while (!b.over) b.runTurn(aiChoices(b, 0) + aiChoices(b, 1))
            when (b.winner) { 0 -> wins[0]++; 1 -> wins[1]++; else -> draws++ }
            turns += b.turn
        }
        return BattleStats(runs, listOf(wins[0].toDouble() / runs, wins[1].toDouble() / runs), draws.toDouble() / runs, turns.toDouble() / runs)
    }
}
