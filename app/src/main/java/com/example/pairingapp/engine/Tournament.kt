package com.example.pairingapp.engine

import kotlin.random.Random

/** Suggested Swiss round count for a field of [players]. */
fun suggestedSwissRounds(players: Int): Int {
    var rounds = 1
    var cap = 2
    while (cap < players) { cap *= 2; rounds++ }
    return if (players < 2) 1 else rounds
}

class Tournament(
    val id: String,
    var name: String,
    val points: PointSystem,
    val stages: List<StageConfig>,
    val players: List<Player>,
    val created: Long = System.currentTimeMillis(),
    val dropped: MutableSet<String> = mutableSetOf(),
    val matches: MutableList<Match> = mutableListOf(),
    val decks: MutableMap<String, String> = mutableMapOf(),
    var status: Status = Status.ACTIVE,
    /** Round clock length in minutes (0 = no timer) and the optional extra time after it runs out. */
    val roundMinutes: Int = 0,
    val extraMinutes: Int = 0,
    private val rng: Random = Random.Default
) {
    // ---------- basic queries ----------

    fun name(id: String) = players.firstOrNull { it.id == id }?.name ?: "?"

    val currentRound: Int get() = matches.maxOfOrNull { it.round } ?: 0
    val currentStage: Int get() = matches.lastOrNull()?.stage ?: 0

    fun roundMatches(r: Int) = matches.filter { it.round == r }
    fun roundComplete(r: Int) = roundMatches(r).all { it.result != Result.PENDING }
    fun pendingCount(r: Int) = roundMatches(r).count { it.result == Result.PENDING }

    fun roundTitle(r: Int): String {
        val ms = roundMatches(r)
        if (ms.isEmpty()) return "Round $r"
        val si = ms.first().stage
        val idx = matches.filter { it.stage == si && it.round <= r }.map { it.round }.distinct().size
        return when (stages[si].type) {
            StageType.SWISS -> "Swiss round $idx"
            StageType.SINGLE_ELIM -> when (val c = ms.size) {
                1 -> "Final"
                2 -> "Semifinals"
                4 -> "Quarterfinals"
                else -> "Round of ${c * 2}"
            }
            StageType.DOUBLE_ELIM -> when {
                ms.any { it.bracket == Bracket.RESET } -> "Grand final (reset)"
                ms.any { it.bracket == Bracket.FINAL } -> "Grand final"
                else -> "Double elim round $idx"
            }
        }
    }

    // ---------- lifecycle ----------

    fun start() {
        if (matches.isEmpty()) startStage(0)
    }

    fun canAdvance() = status == Status.ACTIVE && matches.isNotEmpty() && roundComplete(currentRound)

    fun stageDone(si: Int): Boolean {
        val rs = matches.filter { it.stage == si }
        if (rs.isEmpty()) return false
        val lastRound = rs.maxOf { it.round }
        val last = rs.filter { it.round == lastRound }
        if (last.any { it.result == Result.PENDING }) return false
        return when (stages[si].type) {
            StageType.SWISS -> rs.map { it.round }.distinct().size >= stages[si].swissRounds
            StageType.SINGLE_ELIM -> last.size == 1
            StageType.DOUBLE_ELIM ->
                (last.size == 1 && last[0].isBye) || // a lone entrant has nobody left to play
                    last.any { it.bracket == Bracket.RESET } ||
                    last.any { it.bracket == Bracket.FINAL && it.result == Result.P1_WIN }
        }
    }

    /** Label for the "go on" button once the current round is fully reported. */
    fun nextStepLabel(): String {
        val si = currentStage
        return when {
            !stageDone(si) -> "Next round"
            si + 1 < stages.size -> "Start next stage: ${stages[si + 1].describe()}"
            else -> "Finish tournament"
        }
    }

    fun advance(): Boolean {
        if (!canAdvance()) return false
        val si = currentStage
        when {
            !stageDone(si) -> nextRound(si)
            si + 1 < stages.size -> startStage(si + 1)
            else -> status = Status.FINISHED
        }
        return true
    }

    fun setResult(m: Match, r: Result): Boolean {
        if (status != Status.ACTIVE || m.round != currentRound || m.isBye) return false
        if (m.bracket != Bracket.SWISS && (r == Result.DRAW || r == Result.DOUBLE_LOSS)) return false
        m.result = r
        return true
    }

    fun setDropped(id: String, drop: Boolean) {
        if (drop) dropped.add(id) else dropped.remove(id)
    }

    // ---------- round generation ----------

    private fun add(round: Int, stage: Int, bracket: Bracket, p1: String, p2: String?, table: Int) {
        matches.add(Match(round, stage, bracket, p1, p2, if (p2 == null) Result.P1_WIN else Result.PENDING, table))
    }

    private fun startStage(si: Int) {
        val cfg = stages[si]
        var entrants: List<String> =
            if (si == 0) players.map { it.id }.shuffled(rng)
            else standings().filter { it.player.id !in dropped }.map { it.player.id }
        if (si > 0 && cfg.cut > 0) entrants = entrants.take(cfg.cut)
        if (entrants.isEmpty()) { status = Status.FINISHED; return }
        val round = currentRound + 1
        when (cfg.type) {
            StageType.SWISS -> swissRound(si, round, entrants)
            StageType.SINGLE_ELIM -> seededPairs(entrants).forEachIndexed { i, p -> add(round, si, Bracket.SINGLE, p.first, p.second, i + 1) }
            StageType.DOUBLE_ELIM -> seededPairs(entrants).forEachIndexed { i, p -> add(round, si, Bracket.WINNERS, p.first, p.second, i + 1) }
        }
    }

    private fun nextRound(si: Int) {
        val round = currentRound + 1
        when (stages[si].type) {
            StageType.SWISS -> {
                // The stage's first round holds every entrant, so reinstated players come back.
                val firstRound = matches.filter { it.stage == si }.minOf { it.round }
                val field = matches.filter { it.stage == si && it.round == firstRound }
                    .flatMap { listOfNotNull(it.p1, it.p2) }
                swissRound(si, round, field)
            }
            StageType.SINGLE_ELIM -> {
                val winners = roundMatches(currentRound).mapNotNull { it.winner() }
                pairAdjacent(winners).forEachIndexed { i, p -> add(round, si, Bracket.SINGLE, p.first, p.second, i + 1) }
            }
            StageType.DOUBLE_ELIM -> doubleElimRound(si, round)
        }
    }

    private fun swissRound(si: Int, round: Int, entrants: List<String>) {
        val active = entrants.filter { it !in dropped }
        val swiss = matches.filter { it.bracket == Bracket.SWISS }
        val order: List<String> = if (swiss.isEmpty()) active.shuffled(rng) else {
            val pts = standings().associate { it.player.id to it.points }
            active.shuffled(rng).sortedByDescending { pts[it] ?: 0 }
        }
        val played = swiss.filter { !it.isBye }.map { SwissPairer.key(it.p1, it.p2!!) }.toSet()
        val hadBye = swiss.filter { it.isBye }.map { it.p1 }.toSet()
        val pairing = SwissPairer.pair(order, played, hadBye)
        pairing.pairs.forEachIndexed { i, p -> add(round, si, Bracket.SWISS, p.first, p.second, i + 1) }
        pairing.bye?.let { add(round, si, Bracket.SWISS, it, null, 0) }
    }

    private fun doubleElimRound(si: Int, round: Int) {
        val last = roundMatches(currentRound)
        val finalMatch = last.firstOrNull { it.bracket == Bracket.FINAL }
        if (finalMatch != null) { // WB champion lost the first final -> bracket reset
            add(round, si, Bracket.RESET, finalMatch.p1, finalMatch.p2, 1)
            return
        }
        val wb = last.filter { it.bracket == Bracket.WINNERS }.mapNotNull { it.winner() }
        val droppers = last.filter { it.bracket == Bracket.WINNERS && !it.isBye }.mapNotNull { it.loser() }
        val survivors = last.filter { it.bracket == Bracket.LOSERS }.mapNotNull { it.winner() }
        val pool = survivors + droppers
        if (wb.size == 1 && pool.size == 1) {
            add(round, si, Bracket.FINAL, wb[0], pool[0], 1)
            return
        }
        var table = 1
        for (p in pairAdjacent(wb)) add(round, si, Bracket.WINNERS, p.first, p.second, table++)
        // Losers bracket: veterans meet fresh droppers first, leftovers pair among themselves.
        val n = minOf(survivors.size, droppers.size)
        for (i in 0 until n) add(round, si, Bracket.LOSERS, survivors[i], droppers[i], table++)
        val rest = survivors.drop(n) + droppers.drop(n)
        for (p in pairAdjacent(rest)) add(round, si, Bracket.LOSERS, p.first, p.second, table++)
    }

    private fun pairAdjacent(ids: List<String>): List<Pair<String, String?>> =
        ids.chunked(2).map { it[0] to it.getOrNull(1) }

    /** Standard seeded bracket (1 v last, etc.); top seeds get byes when not a power of two. */
    private fun seededPairs(seeds: List<String>): List<Pair<String, String?>> {
        val n = seeds.size
        if (n == 1) return listOf(seeds[0] to null)
        var m = 2
        while (m < n) m *= 2
        var order = listOf(1)
        var size = 1
        while (size < m) {
            size *= 2
            order = order.flatMap { listOf(it, size + 1 - it) }
        }
        return order.chunked(2).map { (a, b) -> seeds[a - 1] to (if (b <= n) seeds[b - 1] else null) }
    }

    // ---------- standings ----------

    private fun eliminatedBy(m: Match): String? = when (m.bracket) {
        Bracket.SINGLE, Bracket.LOSERS, Bracket.RESET -> m.loser()
        Bracket.FINAL -> if (m.result == Result.P1_WIN) m.p2 else null
        else -> null
    }

    private class Acc {
        var pts = 0; var w = 0; var l = 0; var d = 0; var byes = 0; var played = 0
        val opps = mutableListOf<String>()
    }

    /**
     * Standings as they were after round [upTo]. Points / tiebreakers come from the Swiss rounds
     * (or from everything if there is no Swiss stage). Once an elimination stage starts, its
     * results order its participants above the rest.
     */
    fun standings(upTo: Int = currentRound): List<Standing> {
        val swissMode = stages.any { it.type == StageType.SWISS }
        val done = matches.filter { it.round <= upTo && it.result != Result.PENDING }
        val scored = done.filter { if (swissMode) it.bracket == Bracket.SWISS else !it.isBye }
        val acc = players.associate { it.id to Acc() }
        for (m in scored) {
            val a = acc[m.p1] ?: continue
            if (m.p2 == null) {
                a.pts += points.bye; a.w++; a.byes++; a.played++
                continue
            }
            val b = acc[m.p2] ?: continue
            a.opps.add(m.p2); b.opps.add(m.p1); a.played++; b.played++
            when (m.result) {
                Result.P1_WIN -> { a.pts += points.win; a.w++; b.pts += points.loss; b.l++ }
                Result.P2_WIN -> { b.pts += points.win; b.w++; a.pts += points.loss; a.l++ }
                Result.DRAW -> { a.pts += points.draw; a.d++; b.pts += points.draw; b.d++ }
                Result.DOUBLE_LOSS -> { a.pts += points.doubleLoss; a.l++; b.pts += points.doubleLoss; b.l++ }
                Result.PENDING -> {}
            }
        }
        val mw = players.associate { p ->
            val a = acc.getValue(p.id)
            p.id to if (a.played == 0) 0.0 else maxOf(0.33, (a.w + a.d / 3.0) / a.played)
        }
        val omw = players.associate { p ->
            val o = acc.getValue(p.id).opps
            p.id to if (o.isEmpty()) 0.0 else o.map { mw.getValue(it) }.average()
        }
        val oomw = players.associate { p ->
            val o = acc.getValue(p.id).opps
            p.id to if (o.isEmpty()) 0.0 else o.map { omw.getValue(it) }.average()
        }

        val elimMatches = matches.filter { it.round <= upTo && it.bracket != Bracket.SWISS }
        val participants = elimMatches.flatMap { listOfNotNull(it.p1, it.p2) }.toSet()
        val outRound = HashMap<String, Int>()
        for (m in elimMatches) if (!m.isBye) eliminatedBy(m)?.let { outRound[it] = m.round }
        val champions = HashSet<String>()
        stages.indices.filter { stages[it].type.isElim && stageDone(it) }.forEach { si ->
            val fin = matches.filter { it.stage == si }.let { rs -> rs.filter { it.round == rs.maxOf { r -> r.round } } }
            if (fin.isNotEmpty() && fin.first().round <= upTo) fin.last().winner()?.let { champions.add(it) }
        }
        fun group(id: String) = if (id !in participants) 2 else if (id in outRound) 1 else 0
        fun sub(id: String) = -(outRound[id] ?: 0)

        val sorted = players.sortedWith(
            compareBy<Player> { group(it.id) }
                .thenBy { sub(it.id) }
                .thenByDescending { acc.getValue(it.id).pts }
                .thenByDescending { omw.getValue(it.id) }
                .thenByDescending { oomw.getValue(it.id) }
                .thenByDescending { mw.getValue(it.id) }
                .thenBy { it.name.lowercase() }
        )
        return sorted.mapIndexed { i, p ->
            val a = acc.getValue(p.id)
            val note = when {
                p.id in champions -> "Champion"
                p.id in dropped -> "Dropped"
                p.id in participants && p.id in outRound -> "Out"
                p.id in participants -> "In"
                else -> ""
            }
            Standing(i + 1, p, a.pts, a.w, a.l, a.d, a.byes, mw.getValue(p.id), omw.getValue(p.id), oomw.getValue(p.id),
                p.id in dropped, note, decks[p.id].orEmpty())
        }
    }

    fun standingsText(): String = buildString {
        append(name).append('\n')
        for (s in standings()) {
            append("${s.rank}. ${s.player.name} — ${s.points} pts (${s.wins}-${s.losses}-${s.draws})")
            append(" MW ${pct(s.matchWinPct)} OMW ${pct(s.oppMatchWinPct)} OOMW ${pct(s.oppOppMatchWinPct)}")
            if (s.deck.isNotBlank()) append(" · ${s.deck}")
            append('\n')
        }
    }
}

fun pctNum(v: Double): String = "%.1f".format(v * 100)

fun pct(v: Double): String = pctNum(v) + "%"
