package com.example.pairingapp.engine

object SwissPairer {

    fun key(a: String, b: String) = if (a < b) "$a|$b" else "$b|$a"

    class Pairing(val pairs: List<Pair<String, String>>, val bye: String?)

    /**
     * [sorted] is the field ordered best-first. Pairs neighbours in the standings while avoiding
     * rematches (keys in [played]); falls back to allowing rematches only if unavoidable.
     * With an odd field the bye goes to the lowest-ranked player who has not had one yet.
     */
    fun pair(sorted: List<String>, played: Set<String>, hadBye: Set<String>): Pairing {
        if (sorted.isEmpty()) return Pairing(emptyList(), null)
        val byeIdx: List<Int> =
            if (sorted.size % 2 == 1) sorted.indices.reversed().sortedBy { if (sorted[it] in hadBye) 1 else 0 }
            else listOf(-1)

        // Time-boxed search: each bye candidate gets a slice, the whole search is capped.
        val end = System.nanoTime() + 2_000_000_000L
        for (b in byeIdx) {
            if (System.nanoTime() > end) break
            val rest = sorted.filterIndexed { i, _ -> i != b }
            val out = ArrayList<Pair<String, String>>()
            val deadline = minOf(end, System.nanoTime() + 300_000_000L)
            if (solve(rest.toMutableList(), played, out, deadline)) {
                return Pairing(out, if (b >= 0) sorted[b] else null)
            }
        }
        // Rematch unavoidable: give the bye to the best candidate and pair greedily.
        val b = byeIdx.first()
        val rest = sorted.filterIndexed { i, _ -> i != b }.toMutableList()
        val out = ArrayList<Pair<String, String>>()
        while (rest.isNotEmpty()) {
            val first = rest.removeAt(0)
            val i = rest.indexOfFirst { key(first, it) !in played }.let { if (it < 0) 0 else it }
            out.add(first to rest.removeAt(i))
        }
        return Pairing(out, if (b >= 0) sorted[b] else null)
    }

    private fun solve(list: MutableList<String>, played: Set<String>, out: MutableList<Pair<String, String>>, deadline: Long): Boolean {
        if (list.isEmpty()) return true
        if (System.nanoTime() > deadline) return false
        val first = list[0]
        for (i in 1 until list.size) {
            val cand = list[i]
            if (key(first, cand) in played) continue
            val rest = ArrayList(list)
            rest.removeAt(i); rest.removeAt(0)
            out.add(first to cand)
            if (solve(rest, played, out, deadline)) return true
            out.removeAt(out.size - 1)
        }
        return false
    }
}
