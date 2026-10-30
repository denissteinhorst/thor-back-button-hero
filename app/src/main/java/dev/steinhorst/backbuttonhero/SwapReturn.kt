package dev.steinhorst.backbuttonhero

/**
 * After hold-to-swap, closing one of the two apps would leave the other on the swapped screen and
 * Home on the freed one (a game launched from a frontend, swapped, then quit). The remaining app
 * then moves back, as if the user had swapped back before closing.
 */
object SwapReturn {
    data class Member(val packageName: String, val taskId: Int, val from: Int, val to: Int)

    data class Swap(val first: Member, val second: Member) {
        fun partnerOf(member: Member) = if (member == first) second else first
    }

    enum class Decision { RETURN, KEEP, DROP }

    /** Only a pair of verified task moves can be undone; a relaunch fallback has no task identity. */
    fun swap(moves: List<TaskMovePolicy.Move>, topDisplay: Int, bottomDisplay: Int): Swap? {
        if (moves.size != 2) return null
        val members = moves.map { move ->
            val task = move.taskId ?: return null
            val from = if (move.targetDisplay == bottomDisplay) topDisplay else bottomDisplay
            Member(move.packageName, task, from, move.targetDisplay)
        }
        return Swap(members[0], members[1]).takeIf { members[0].to != members[1].to }
    }

    /** The member whose screen now shows no app; only then could it have been closed. */
    fun freed(swap: Swap, topApp: (Int) -> String?): Member? =
        listOf(swap.first, swap.second).firstOrNull { topApp(it.to) == null }

    /**
     * Decides from two TaskParker queries; a failed query is never mistaken for a closed task.
     * A closed task that still exists was merely left (Home); a partner elsewhere needs no return.
     */
    fun decide(closedResult: String?, partner: Member, partnerResult: String?): Decision = when {
        !answered(closedResult) || !answered(partnerResult) -> Decision.KEEP
        closedResult != GONE -> Decision.KEEP
        display(partnerResult) == partner.to -> Decision.RETURN
        else -> Decision.DROP
    }

    private fun answered(result: String?) = result == GONE || display(result) != null

    private fun display(result: String?): Int? =
        result?.takeIf { it.startsWith("OK display=") }?.removePrefix("OK display=")?.toIntOrNull()

    private const val GONE = "ERR task unavailable"
}
