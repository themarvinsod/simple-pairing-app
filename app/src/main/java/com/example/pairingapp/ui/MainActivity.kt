package com.example.pairingapp.ui

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import android.text.InputType
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import com.example.pairingapp.engine.Bracket
import com.example.pairingapp.engine.Match
import com.example.pairingapp.engine.Player
import com.example.pairingapp.engine.PointSystem
import com.example.pairingapp.engine.Result
import com.example.pairingapp.engine.StageConfig
import com.example.pairingapp.engine.StageType
import com.example.pairingapp.engine.Standing
import com.example.pairingapp.engine.Status
import com.example.pairingapp.engine.Store
import com.example.pairingapp.engine.Tournament
import com.example.pairingapp.engine.pctNum
import com.example.pairingapp.engine.suggestedSwissRounds

class MainActivity : AppCompatActivity() {

    private class StageDraft(var type: StageType, var n: Int)

    private class Draft {
        var name = ""
        var win = "3"; var draw = "1"; var loss = "0"; var doubleLoss = "0"; var bye = "3"
        val stages = mutableListOf(StageDraft(StageType.SWISS, 4))
        val selected = mutableSetOf<String>()
    }

    private lateinit var store: Store
    private lateinit var root: FrameLayout
    private val stack = ArrayList<() -> View>()
    private var scroll: ScrollView? = null

    private var draft = Draft()
    private var viewRound = 0
    private var tab = 0 // 0 = pairings, 1 = standings
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        root = FrameLayout(this)
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val i = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(i.left, i.top, i.right, i.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (stack.size > 1) pop() else { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
            }
        })
        push { home() }
    }

    override fun onPause() {
        super.onPause()
        store.saveNow()
    }

    // ---------- navigation ----------

    private fun push(screen: () -> View) { stack.add(screen); render(false) }
    private fun pop() { store.save(); stack.removeAt(stack.size - 1); render(false) }
    private fun rerender() = render(true)

    private fun render(keepScroll: Boolean) {
        val y = if (keepScroll) scroll?.scrollY ?: 0 else 0
        root.removeAllViews()
        val build: () -> View = stack[stack.size - 1]
        root.addView(build())
        if (y > 0) scroll?.post { scroll?.scrollTo(0, y) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun scaffold(title: String, canBack: Boolean, bottom: View? = null, body: LinearLayout.() -> Unit): View {
        val page = vbox()
        val header = hbox().apply {
            setPadding(dp(8), dp(10), dp(16), dp(14))
            background = banner()
        }
        if (canBack) header.add(button("←", true) { pop() }.apply {
            setTextColor(android.graphics.Color.WHITE); strokeColor = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
        }, w = WRAP_CONTENT, end = 8)
        else header.setPadding(dp(20), dp(16), dp(16), dp(18))
        header.add(text(title, 22f, true).apply {
            setTextColor(android.graphics.Color.WHITE)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, 1f)
        page.addView(header)
        val content = vbox(16).apply(body)
        val sv = ScrollView(this).apply { addView(content) }
        scroll = sv
        page.addView(sv, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        bottom?.let { page.addView(it) }
        return page
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Yes") { _, _ -> action() }.setNegativeButton("Cancel", null).show()
    }

    // ---------- home ----------

    private fun home(): View = scaffold("Pairing", false) {
        val row = hbox()
        row.add(button("New tournament") { draft = Draft(); push { setup() } }, 1f, end = 8)
        row.add(button("Players", true) { push { playersScreen() } }, 1f)
        add(row)
        if (store.tournaments.isEmpty()) add(text("No tournaments yet. Tap “New tournament” to start one.", muted = true), top = 20)
        store.tournaments.sortedByDescending { it.created }.forEach { t ->
            val c = card()
            c.add(text(t.name, 17f, true))
            val state = if (t.status == Status.FINISHED) "Finished" else "${t.roundTitle(t.currentRound)} in progress"
            c.add(text("${t.players.size} players · $state", 13f, muted = true), top = 2)
            c.add(text(t.stages.joinToString("  →  ") { it.describe() }, 12f, muted = true), top = 2)
            c.setOnClickListener { openTournament(t) }
            c.setOnLongClickListener {
                confirm("Delete tournament?", "“${t.name}” and all its results will be removed.") {
                    store.tournaments.remove(t); store.save(); rerender()
                }
                true
            }
            add(c, top = 10)
        }
        if (store.tournaments.isNotEmpty()) add(text("Long-press a tournament to delete it.", 12f, muted = true), top = 12)
    }

    private fun openTournament(t: Tournament) {
        viewRound = t.currentRound
        tab = 0
        push { tournamentScreen(t) }
    }

    // ---------- players ----------

    private fun playersScreen(): View = scaffold("Players", true) {
        val field = input("New player name")
        fun doAdd() {
            val n = field.text.toString()
            if (n.isBlank()) return
            if (store.addPlayer(n) == null) toast("Already registered") else rerender()
        }
        field.imeOptions = EditorInfo.IME_ACTION_DONE
        field.setOnEditorActionListener { _, _, _ -> doAdd(); true }
        val addRow = hbox()
        addRow.add(field, 1f, end = 8)
        addRow.add(button("Add") { doAdd() }, w = WRAP_CONTENT)
        add(addRow)
        add(text("${store.roster.size} registered", 13f, muted = true), top = 12)
        store.roster.sortedBy { it.name.lowercase() }.forEach { p ->
            val r = hbox().apply { setPadding(0, dp(2), 0, dp(2)) }
            r.add(text(p.name, 16f), 1f)
            r.add(button("✕", true) {
                confirm("Remove player?", "Remove ${p.name} from the registered list? Past tournaments keep their results.") {
                    store.removePlayer(p); rerender()
                }
            }, w = WRAP_CONTENT)
            add(r, top = 4)
        }
    }

    // ---------- new tournament ----------

    private fun labeled(label: String, initial: String, onChange: (String) -> Unit): View = vbox().apply {
        add(text(label, 12f, muted = true))
        add(input(label, initial, number = true, signed = true, onChange = onChange))
    }

    private fun setup(): View {
        val d = draft
        val start = vbox(12).apply { add(button("Start tournament") { startTournament() }) }
        return scaffold("New tournament", true, start) {
            add(text("Name", 13f, muted = true))
            add(input("e.g. Friday Night Event", d.name) { d.name = it })

            add(text("Points per result", 15f, true), top = 16)
            val r1 = hbox()
            r1.add(labeled("Win", d.win) { d.win = it }, 1f, end = 8)
            r1.add(labeled("Draw", d.draw) { d.draw = it }, 1f, end = 8)
            r1.add(labeled("Loss", d.loss) { d.loss = it }, 1f)
            add(r1, top = 4)
            val r2 = hbox()
            r2.add(labeled("Double loss", d.doubleLoss) { d.doubleLoss = it }, 1f, end = 8)
            r2.add(labeled("Bye", d.bye) { d.bye = it }, 1f, end = 8)
            r2.add(View(this@MainActivity), 1f)
            add(r2, top = 4)
            add(text("Double loss: both players lose the match (e.g. both get a game loss). Bye: points for a free win.", 12f, muted = true), top = 4)

            add(text("Stages", 15f, true), top = 16)
            add(text("Chain stages to mix formats, e.g. Swiss → Top 8 single elimination.", 12f, muted = true))
            d.stages.forEachIndexed { i, sd -> add(stageRow(d, i, sd), top = 8) }
            add(button("+ Add stage", true) {
                d.stages.add(StageDraft(StageType.SINGLE_ELIM, 8)); rerender()
            }, top = 8)

            add(text("Players", 15f, true), top = 16)
            val info = text("", 13f, muted = true)
            fun refresh() {
                val n = d.selected.size
                info.text = "$n selected" + if (n >= 2) " · suggested Swiss rounds: ${suggestedSwissRounds(n)}" else ""
            }
            refresh()
            add(info)

            // New participant input, right at the top of the players section.
            val field = input("New participant name")
            fun addNew() {
                val n = field.text.toString().trim()
                if (n.isEmpty()) return
                val p = store.addPlayer(n) ?: store.roster.firstOrNull { it.name.equals(n, true) }
                p?.let { d.selected.add(it.id) }
                rerender()
            }
            field.imeOptions = EditorInfo.IME_ACTION_DONE
            field.setOnEditorActionListener { _, _, _ -> addNew(); true }
            val addCard = card()
            addCard.add(text("Add new participant", 13f, true))
            val addRow = hbox()
            addRow.add(field, 1f, end = 8)
            addRow.add(button("Add", accent = true) { addNew() }, w = WRAP_CONTENT)
            addCard.add(addRow)
            add(addCard, top = 6)

            val sel = hbox()
            sel.add(button("Select all", true) { d.selected.addAll(store.roster.map { it.id }); rerender() }, 1f, end = 8)
            sel.add(button("Clear", true) { d.selected.clear(); rerender() }, 1f)
            add(sel, top = 8)
            if (store.roster.isEmpty()) add(text("No registered players yet — add some above.", muted = true), top = 8)
            store.roster.sortedBy { it.name.lowercase() }.forEach { p ->
                add(CheckBox(this@MainActivity).apply {
                    text = p.name
                    textSize = 16f
                    isChecked = p.id in d.selected
                    setOnCheckedChangeListener { _, on -> if (on) d.selected.add(p.id) else d.selected.remove(p.id); refresh() }
                })
            }
        }
    }

    private fun stageRow(d: Draft, index: Int, sd: StageDraft): View {
        val c = card()
        val row = hbox()
        val types = StageType.values()
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, types.map { it.label })
            setSelection(sd.type.ordinal, false)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (pos == sd.type.ordinal) return
                    sd.type = types[pos]
                    sd.n = if (!sd.type.isElim) 4 else if (index == 0) 0 else 8
                    rerender()
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        row.add(spinner, 1f, end = 8)
        if (d.stages.size > 1) row.add(button("✕", true) { d.stages.removeAt(index); rerender() }, w = WRAP_CONTENT)
        c.add(row)
        when {
            !sd.type.isElim -> {
                c.add(text("Rounds", 12f, muted = true), top = 4)
                c.add(input("Rounds", sd.n.toString(), number = true) { sd.n = it.toIntOrNull() ?: 0 })
            }
            index == 0 -> c.add(text("Everyone registered enters, seeded randomly.", 12f, muted = true), top = 4)
            else -> {
                c.add(text("Top N from the standings advance (0 = everyone still active)", 12f, muted = true), top = 4)
                c.add(input("Top N", sd.n.toString(), number = true) { sd.n = it.toIntOrNull() ?: 0 })
            }
        }
        return c
    }

    private fun startTournament() {
        val d = draft
        val pts = listOf(d.win, d.draw, d.loss, d.doubleLoss, d.bye).map { it.trim().toIntOrNull() }
        if (pts.any { it == null }) { toast("Points must be whole numbers"); return }
        val players: List<Player> = store.roster.filter { it.id in d.selected }
        if (players.size < 2) { toast("Select at least 2 players"); return }
        val stages = ArrayList<StageConfig>()
        d.stages.forEachIndexed { i, sd ->
            if (!sd.type.isElim) {
                if (sd.n < 1) { toast("Swiss needs at least 1 round"); return }
                stages.add(StageConfig(sd.type, sd.n, 0))
            } else {
                if (i > 0 && sd.n != 0 && sd.n < 2) { toast("Top N must be 0 or at least 2"); return }
                stages.add(StageConfig(sd.type, 0, if (i == 0) 0 else sd.n))
            }
        }
        val t = Tournament(
            store.newId(), d.name.trim().ifEmpty { "Tournament ${store.tournaments.size + 1}" },
            PointSystem(pts[0]!!, pts[1]!!, pts[2]!!, pts[3]!!, pts[4]!!), stages, players
        )
        t.start()
        store.tournaments.add(t)
        store.save()
        stack.removeAt(stack.size - 1)
        openTournament(t)
    }

    // ---------- tournament ----------

    private fun tournamentScreen(t: Tournament): View {
        val rounds = t.currentRound
        if (viewRound !in 1..rounds) viewRound = rounds
        val editable = t.status == Status.ACTIVE && viewRound == rounds
        return scaffold(t.name, true, bottomBar(t)) {
            val nav = hbox()
            nav.add(button("◀", true) { if (viewRound > 1) { viewRound--; rerender() } }, w = WRAP_CONTENT)
            nav.add(text("Round $viewRound of $rounds\n${t.roundTitle(viewRound)}", 15f, true).apply { gravity = Gravity.CENTER }, 1f)
            nav.add(button("▶", true) { if (viewRound < rounds) { viewRound++; rerender() } }, w = WRAP_CONTENT)
            add(nav)
            val tabs = hbox()
            tabs.add(button("Pairings", tab != 0) { tab = 0; rerender() }, 1f, end = 8)
            tabs.add(button("Standings", tab != 1) { tab = 1; rerender() }, 1f)
            add(tabs, top = 8)
            if (tab == 0) pairingsBody(t, editable) else standingsBody(t)
        }
    }

    private fun bottomBar(t: Tournament): View {
        val box = vbox(12)
        when {
            t.status == Status.FINISHED -> {
                val row = hbox()
                row.add(button("Deck names") { push { decksScreen(t) } }, 1f, end = 8)
                row.add(button("Share results", true) { share(t) }, 1f)
                box.add(row)
            }
            viewRound != t.currentRound -> box.add(button("Go to current round") { viewRound = t.currentRound; rerender() })
            else -> {
                val ok = t.canAdvance()
                val b = button(if (ok) t.nextStepLabel() else "${t.pendingCount(t.currentRound)} result(s) still missing") {
                    // Pairing is time-capped (2 s) in SwissPairer, so it is safe on the UI thread;
                    // that keeps Tournament single-threaded. Save immediately so a new round is never lost.
                    t.advance()
                    store.saveNow()
                    viewRound = t.currentRound
                    if (t.status == Status.FINISHED) {
                        tab = 1
                        rerender()
                        push { decksScreen(t) }
                    } else { tab = 0; rerender() }
                }
                b.maxLines = 2
                b.isEnabled = ok
                box.add(b)
            }
        }
        return box
    }

    private fun share(t: Tournament) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t.standingsText())
        startActivity(Intent.createChooser(send, "Share results"))
    }

    private fun LinearLayout.pairingsBody(t: Tournament, editable: Boolean) {
        t.roundMatches(viewRound).forEach { m -> add(matchCard(t, m, editable), top = 10) }
    }

    private fun matchCard(t: Tournament, m: Match, editable: Boolean): View {
        val c = card()
        val where = if (m.isBye) m.bracket.label else "Table ${m.table} · ${m.bracket.label}"
        c.add(text(where, 12f, muted = true))
        val a = t.name(m.p1)
        val b = m.p2?.let { t.name(it) }
        when {
            b == null -> {
                c.add(text("$a — bye", 16f, true), top = 4)
                c.add(text(if (m.bracket == Bracket.SWISS) "Awarded ${t.points.bye} pts" else "Advances automatically", 13f, muted = true))
            }
            editable -> {
                val row = hbox()
                row.add(resultButton(t, m, Result.P1_WIN, a), 1f, end = 4)
                row.add(text("vs", 13f, muted = true), w = WRAP_CONTENT)
                row.add(resultButton(t, m, Result.P2_WIN, b), 1f, start = 4)
                c.add(row, top = 4)
                if (m.bracket == Bracket.SWISS) {
                    val row2 = hbox()
                    row2.add(resultButton(t, m, Result.DRAW, "Draw"), 1f, end = 8)
                    row2.add(resultButton(t, m, Result.DOUBLE_LOSS, "Double loss"), 1f)
                    c.add(row2, top = 4)
                }
            }
            else -> {
                c.add(text("$a  vs  $b", 16f, true), top = 4)
                c.add(text(
                    when (m.result) {
                        Result.P1_WIN -> "$a won"
                        Result.P2_WIN -> "$b won"
                        Result.DRAW -> "Draw"
                        Result.DOUBLE_LOSS -> "Double loss"
                        Result.PENDING -> "No result"
                    }, 13f, muted = true
                ))
            }
        }
        return c
    }

    private fun resultButton(t: Tournament, m: Match, r: Result, label: String) =
        button(if (m.result == r) "✓ $label" else label, outlined = m.result != r, accent = m.result == r) {
            if (t.setResult(m, if (m.result == r) Result.PENDING else r)) { store.save(); rerender() }
        }

    private fun LinearLayout.standingsBody(t: Tournament) {
        val rows = t.standings(viewRound)
        val canDrop = t.status == Status.ACTIVE && viewRound == t.currentRound &&
            t.stages[t.currentStage].type == StageType.SWISS
        add(text("After ${t.roundTitle(viewRound)}" + if (canDrop) " · tap a player to drop / reinstate" else "", 12f, muted = true), top = 10)

        // Fixed-width rank + weighted columns so everything always fits the screen width.
        val wName = 3.3f; val wPts = 1f; val wPct = 1.2f
        fun num(s: String, bold: Boolean = false, color: Int? = null) = text(s, 12.5f, bold).apply {
            gravity = Gravity.END; maxLines = 1
            color?.let { setTextColor(it) }
        }

        val head = hbox().apply { setPadding(dp(10), dp(8), dp(10), 0) }
        head.addView(View(this@MainActivity), LinearLayout.LayoutParams(dp(34), 1))
        head.add(text("PLAYER", 10.5f, true, true), wName)
        listOf("PTS" to wPts, "MW%" to wPct, "OMW%" to wPct, "OOMW%" to wPct).forEach { (h, w) ->
            head.add(text(h, 10.5f, true, true).apply { gravity = Gravity.END; maxLines = 1 }, w)
        }
        add(head, top = 4)

        val medal = intArrayOf(0xFFFFC857.toInt(), 0xFFC4CAD8.toInt(), 0xFFD8925B.toInt())
        rows.forEach { s: Standing ->
            val champion = s.note == "Champion"
            val r = hbox().apply {
                setPadding(dp(10), dp(9), dp(10), dp(9))
                alpha = if (s.dropped) 0.5f else 1f
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(14).toFloat()
                    setColor(cSurface)
                    setStroke(dp(if (champion) 2 else 1), if (champion) cAccent else androidx.core.graphics.ColorUtils.setAlphaComponent(cPrimary, 70))
                }
            }
            val top3 = s.rank <= 3
            r.addView(
                badge(s.rank.toString(), if (top3) medal[s.rank - 1] else androidx.core.graphics.ColorUtils.setAlphaComponent(cPrimary, 110),
                    if (top3) android.graphics.Color.BLACK else cOnSurface),
                LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginEnd = dp(8) }
            )
            val nameCol = vbox()
            nameCol.add(text(s.player.name, 14.5f, true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            val sub = listOfNotNull(
                "${s.wins}-${s.losses}-${s.draws}",
                s.deck.takeIf { it.isNotBlank() },
                (if (s.dropped) "Dropped" else s.note).takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            nameCol.add(text(sub, 11f, muted = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            r.add(nameCol, wName)
            r.add(num(s.points.toString(), true, cAccent), wPts)
            r.add(num(pctNum(s.matchWinPct)), wPct)
            r.add(num(pctNum(s.oppMatchWinPct)), wPct)
            r.add(num(pctNum(s.oppOppMatchWinPct)), wPct)
            if (canDrop) r.setOnClickListener { dropDialog(t, s) }
            add(r, top = 6)
        }
        add(text("MW% = match win · OMW% = opponents' match win · OOMW% = opponents' opponents'", 10.5f, muted = true), top = 10)
    }

    private fun dropDialog(t: Tournament, s: Standing) {
        val label = if (s.dropped) "Reinstate player" else "Drop from tournament"
        AlertDialog.Builder(this).setTitle(s.player.name)
            .setItems(arrayOf(label)) { _, _ ->
                t.setDropped(s.player.id, !s.dropped)
                store.save()
                toast("Takes effect from the next round")
                rerender()
            }.setNegativeButton("Cancel", null).show()
    }

    // ---------- decks ----------

    private fun decksScreen(t: Tournament): View {
        val done = vbox(12).apply { add(button("Done") { pop() }) }
        val suggestions = store.knownDecks()
        return scaffold("Deck names", true, done) {
            add(text("Which deck did each player run? Optional — you can come back later.", 13f, muted = true))
            t.standings().forEach { s ->
                add(text("${s.rank}. ${s.player.name}", 15f, true), top = 12)
                add(AutoCompleteTextView(this@MainActivity).apply {
                    hint = "Deck name"
                    setSingleLine()
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                    threshold = 1
                    setAdapter(ArrayAdapter(this@MainActivity, android.R.layout.simple_dropdown_item_1line, suggestions))
                    setText(t.decks[s.player.id].orEmpty())
                    doAfterTextChanged {
                        val v = it?.toString().orEmpty().trim()
                        if (v.isEmpty()) t.decks.remove(s.player.id) else t.decks[s.player.id] = v
                    }
                })
            }
        }
    }
}
