package com.example.pairingapp.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Saves the player roster and all tournaments as JSON in SharedPreferences. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("pairing_store", Context.MODE_PRIVATE)

    val roster: MutableList<Player> = mutableListOf()
    val tournaments: MutableList<Tournament> = mutableListOf()

    private val handler = Handler(Looper.getMainLooper())
    private val flush = Runnable { saveNow() }

    init {
        val rawRoster = prefs.getString("roster", "[]")
        val rawTournaments = prefs.getString("tournaments", "[]")
        var failed = false
        runCatching {
            JSONArray(rawRoster).let { a ->
                for (i in 0 until a.length()) a.getJSONObject(i).let { roster.add(Player(it.getString("id"), it.getString("name"))) }
            }
        }.onFailure { failed = true }
        runCatching {
            JSONArray(rawTournaments).let { a ->
                for (i in 0 until a.length()) {
                    // A bad record is skipped, not allowed to hide the good ones.
                    runCatching { readTournament(a.getJSONObject(i)) }.onSuccess { tournaments.add(it) }.onFailure { failed = true }
                }
            }
        }.onFailure { failed = true }
        // Keep the untouched raw data so the next save() cannot destroy it.
        if (failed && !prefs.contains("backup_tournaments")) {
            prefs.edit().putString("backup_roster", rawRoster).putString("backup_tournaments", rawTournaments).apply()
        }
    }

    fun newId() = UUID.randomUUID().toString().take(8)

    fun addPlayer(name: String): Player? {
        val n = name.trim()
        if (n.isEmpty() || roster.any { it.name.equals(n, true) }) return null
        return Player(newId(), n).also { roster.add(it); save() }
    }

    fun removePlayer(p: Player) { roster.remove(p); save() }

    /** Every deck name ever typed, for autocomplete. */
    fun knownDecks(): List<String> =
        tournaments.flatMap { it.decks.values }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()

    /** Debounced: taps within 500 ms share one write. [saveNow] flushes immediately. */
    fun save() {
        handler.removeCallbacks(flush)
        handler.postDelayed(flush, 500)
    }

    fun saveNow() {
        handler.removeCallbacks(flush)
        val r = JSONArray()
        roster.forEach { r.put(JSONObject().put("id", it.id).put("name", it.name)) }
        val t = JSONArray()
        tournaments.forEach { t.put(writeTournament(it)) }
        prefs.edit().putString("roster", r.toString()).putString("tournaments", t.toString()).apply()
    }

    private fun writeTournament(t: Tournament) = JSONObject().apply {
        put("id", t.id); put("name", t.name); put("created", t.created); put("status", t.status.name)
        put("points", JSONObject().put("win", t.points.win).put("draw", t.points.draw).put("loss", t.points.loss)
            .put("doubleLoss", t.points.doubleLoss).put("bye", t.points.bye))
        put("stages", JSONArray().also { a ->
            t.stages.forEach { a.put(JSONObject().put("type", it.type.name).put("rounds", it.swissRounds).put("cut", it.cut)) }
        })
        put("players", JSONArray().also { a -> t.players.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name)) } })
        put("dropped", JSONArray(t.dropped.toList()))
        put("decks", JSONObject().also { o -> t.decks.forEach { (k, v) -> o.put(k, v) } })
        put("matches", JSONArray().also { a ->
            t.matches.forEach {
                a.put(JSONObject().put("r", it.round).put("s", it.stage).put("b", it.bracket.name).put("p1", it.p1)
                    .put("p2", it.p2 ?: JSONObject.NULL).put("res", it.result.name).put("t", it.table))
            }
        })
    }

    private fun readTournament(o: JSONObject): Tournament {
        val pt = o.getJSONObject("points")
        val stages = o.getJSONArray("stages").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { s -> StageConfig(StageType.valueOf(s.getString("type")), s.getInt("rounds"), s.getInt("cut")) } }
        }
        val players = o.getJSONArray("players").let { a ->
            (0 until a.length()).map { a.getJSONObject(it).let { p -> Player(p.getString("id"), p.getString("name")) } }
        }
        val dropped = o.getJSONArray("dropped").let { a -> (0 until a.length()).map { a.getString(it) }.toMutableSet() }
        val decks = mutableMapOf<String, String>()
        o.getJSONObject("decks").let { d -> d.keys().forEach { decks[it] = d.getString(it) } }
        val matches = o.getJSONArray("matches").let { a ->
            (0 until a.length()).map {
                a.getJSONObject(it).let { m ->
                    Match(m.getInt("r"), m.getInt("s"), Bracket.valueOf(m.getString("b")), m.getString("p1"),
                        if (m.isNull("p2")) null else m.getString("p2"), Result.valueOf(m.getString("res")), m.getInt("t"))
                }
            }.toMutableList()
        }
        return Tournament(
            o.getString("id"), o.getString("name"),
            PointSystem(pt.getInt("win"), pt.getInt("draw"), pt.getInt("loss"), pt.getInt("doubleLoss"), pt.getInt("bye")),
            stages, players, o.getLong("created"), dropped, matches, decks, Status.valueOf(o.getString("status"))
        )
    }
}
