package dev.steinhorst.backbuttonhero

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwapReturnTest {
    private val game = SwapReturn.Member("dev.example.game", 273, 0, 4)
    private val companion = SwapReturn.Member("dev.example.companion", 271, 4, 0)
    private val swap = SwapReturn.Swap(game, companion)

    @Test
    fun `a swap of two verified task moves records where each app came from`() {
        val moves = listOf(TaskMovePolicy.Move(game.packageName, 4, 273), TaskMovePolicy.Move(companion.packageName, 0, 271))
        assertEquals(swap, SwapReturn.swap(moves, 0, 4))
        assertEquals(companion, swap.partnerOf(game))
        assertEquals(game, swap.partnerOf(companion))
    }

    @Test
    fun `one-sided swaps and relaunch fallbacks without task identity are not recorded`() {
        assertNull(SwapReturn.swap(listOf(TaskMovePolicy.Move(game.packageName, 4, 273)), 0, 4))
        assertNull(SwapReturn.swap(listOf(TaskMovePolicy.Move(game.packageName, 4, null),
            TaskMovePolicy.Move(companion.packageName, 0, 271)), 0, 4))
    }

    @Test
    fun `only a member whose screen shows no app can have been closed`() {
        assertEquals(game, SwapReturn.freed(swap) { if (it == 4) null else companion.packageName })
        assertEquals(companion, SwapReturn.freed(swap) { if (it == 0) null else game.packageName })
        assertNull(SwapReturn.freed(swap) { "dev.example.other" })
    }

    @Test
    fun `a closed app returns its partner while it still sits on the swapped screen`() {
        assertEquals(SwapReturn.Decision.RETURN, SwapReturn.decide("ERR task unavailable", companion, "OK display=0"))
    }

    @Test
    fun `an app merely left for Home keeps the swap and a moved or closed partner drops it`() {
        assertEquals(SwapReturn.Decision.KEEP, SwapReturn.decide("OK display=4", companion, "OK display=0"))
        assertEquals(SwapReturn.Decision.DROP, SwapReturn.decide("ERR task unavailable", companion, "OK display=4"))
        assertEquals(SwapReturn.Decision.DROP, SwapReturn.decide("ERR task unavailable", companion, "ERR task unavailable"))
    }

    @Test
    fun `a failed query is never mistaken for a closed app`() {
        assertEquals(SwapReturn.Decision.KEEP, SwapReturn.decide(null, companion, "OK display=0"))
        assertEquals(SwapReturn.Decision.KEEP, SwapReturn.decide("ERR SecurityException: denied", companion, "OK display=0"))
        assertEquals(SwapReturn.Decision.KEEP, SwapReturn.decide("ERR task unavailable", companion, null))
    }
}
