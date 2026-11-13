package com.example.pairingapp.engine

enum class StageType(val label: String) {
    SWISS("Swiss"),
    SINGLE_ELIM("Single elimination"),
    DOUBLE_ELIM("Double elimination");

    val isElim get() = this != SWISS
}

enum class Result { PENDING, P1_WIN, P2_WIN, DRAW, DOUBLE_LOSS }

enum class Bracket(val label: String) {
    SWISS("Swiss"),
    SINGLE("Bracket"),
    WINNERS("Winners"),
    LOSERS("Losers"),
    FINAL("Grand final"),
    RESET("Grand final (reset)")
}

enum class Status { ACTIVE, FINISHED }

data class Player(val id: String, val name: String)

/** Points awarded per match outcome. Fully user-defined. */
data class PointSystem(
    val win: Int = 3,
    val draw: Int = 1,
    val loss: Int = 0,
    val doubleLoss: Int = 0,
    val bye: Int = 3
)

/**
 * One stage of a tournament.
 * SWISS: [swissRounds] rounds.
 * Elimination: top [cut] players of the standings so far enter (0 = everyone still active).
 */
data class StageConfig(val type: StageType, val swissRounds: Int = 0, val cut: Int = 0) {
    fun describe(): String = when (type) {
        StageType.SWISS -> "Swiss · $swissRounds rounds"
        else -> type.label + if (cut > 0) " · Top $cut" else " · everyone"
    }
}

class Match(
    val round: Int,
    val stage: Int,
    val bracket: Bracket,
    val p1: String,
    val p2: String?,
    var result: Result,
    val table: Int
) {
    val isBye get() = p2 == null

    fun winner(): String? = when {
        isBye -> p1
        result == Result.P1_WIN -> p1
        result == Result.P2_WIN -> p2
        else -> null
    }

    fun loser(): String? = when {
        isBye -> null
        result == Result.P1_WIN -> p2
        result == Result.P2_WIN -> p1
        else -> null
    }
}

data class Standing(
    val rank: Int,
    val player: Player,
    val points: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int,
    val byes: Int,
    val matchWinPct: Double,
    val oppMatchWinPct: Double,
    val oppOppMatchWinPct: Double,
    val dropped: Boolean,
    val note: String,
    val deck: String
)
