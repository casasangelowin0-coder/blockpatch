package com.blockpatch.game

import java.util.Collections
import java.util.Random

/** Lifetime counters used by badges and the daily quest board. */
object Stat {
    const val PLACE = 0
    const val BREAK = 1
    const val HARVEST = 2
    const val FISH = 3
    const val SELL = 4
    const val LOCK = 5
    const val CRAFT = 6
    const val LEVEL = 7
    const val PET = 8
    const val GEMS = 9
    const val WORLDS = 10
    const val SIZE = 11
}

data class Achievement(
    val id: Int,
    val name: String,
    val desc: String,
    val stat: Int,
    val goal: Int,
    val reward: Int,
    val icon: Int
)

object Achievements {
    val all: List<Achievement> = listOf(
        Achievement(1, "First Steps", "Place your first block.", Stat.PLACE, 1, 10, Id.DIRT),
        Achievement(2, "Builder", "Place 100 blocks.", Stat.PLACE, 100, 60, Id.BRICK),
        Achievement(3, "Demolition", "Break 100 blocks.", Stat.BREAK, 100, 60, Id.PICKAXE),
        Achievement(4, "Green Thumb", "Harvest 10 trees.", Stat.HARVEST, 10, 40, Id.TREE),
        Achievement(5, "Farmer", "Harvest 100 trees.", Stat.HARVEST, 100, 220, Id.TREE),
        Achievement(6, "Gone Fishing", "Catch your first fish.", Stat.FISH, 1, 20, Id.FISH_SARDINE),
        Achievement(7, "Master Angler", "Catch 25 fish.", Stat.FISH, 25, 180, Id.FISH_KOI),
        Achievement(8, "Merchant", "Sell 25 items.", Stat.SELL, 25, 100, Id.T_GEM),
        Achievement(9, "Landowner", "Place your first lock.", Stat.LOCK, 1, 30, Id.WORLD_LOCK),
        Achievement(10, "Architect", "Place 10 locks.", Stat.LOCK, 10, 180, Id.BUILDER_LOCK),
        Achievement(11, "Crafter", "Craft 5 items.", Stat.CRAFT, 5, 80, Id.GLASS),
        Achievement(12, "Veteran", "Level up 10 times.", Stat.LEVEL, 10, 200, Id.CROWN),
        Achievement(13, "Companion", "Summon a pet.", Stat.PET, 1, 50, Id.PET_DOG),
        Achievement(14, "Treasure Hunter", "Earn 1,000 gems.", Stat.GEMS, 1000, 150, Id.T_GEM),
        Achievement(15, "Explorer", "Visit 10 worlds.", Stat.WORLDS, 10, 120, Id.DOOR)
    )
}

data class DailyQuest(
    val stat: Int,
    val title: String,
    val goal: Int,
    val reward: Int
)

/** Three deterministic quests per UTC day, so the whole day shows the same board. */
object Daily {
    private val pool = listOf(
        DailyQuest(Stat.PLACE, "Place 20 blocks", 20, 45),
        DailyQuest(Stat.BREAK, "Break 20 blocks", 20, 45),
        DailyQuest(Stat.HARVEST, "Harvest 5 trees", 5, 55),
        DailyQuest(Stat.FISH, "Catch 3 fish", 3, 70),
        DailyQuest(Stat.SELL, "Sell 5 items", 5, 55),
        DailyQuest(Stat.CRAFT, "Craft 2 items", 2, 45),
        DailyQuest(Stat.LOCK, "Place a lock", 1, 60)
    )

    fun today(): List<DailyQuest> {
        val day = System.currentTimeMillis() / 86_400_000L
        val order = pool.indices.toMutableList()
        Collections.shuffle(order, Random(day * 1_103_515_245L + 12_345L))
        return order.take(3).map { pool[it] }
    }
}
