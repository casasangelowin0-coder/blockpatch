package com.blockpatch.game

/** Item / tile ids. Block ids double as foreground tile ids and atlas indices. */
object Id {
    const val AIR = 0
    const val DIRT = 1
    const val ROCK = 2
    const val WOOD = 3
    const val BRICK = 4
    const val GLASS = 5
    const val GOLD = 6
    const val SAND = 7
    const val LAVA = 8
    const val BEDROCK = 9
    const val PLATFORM = 10
    const val DOOR = 11
    const val SIGN = 12
    const val MAIN_DOOR = 13
    const val TREE = 14
    const val WORLD_LOCK = 15
    const val DIAMOND_LOCK = 16
    const val BLUE_LOCK = 17
    const val WINGS_ANGEL = 32
    const val WINGS_DEMON = 33
    const val TOP_HAT = 34
    const val CROWN = 35
    const val SHADES = 36
    const val SNEAKERS = 37
    const val PICKAXE = 38
    const val FIST = 39
    const val SEED = 1000   // seed id = SEED + block id

    // atlas-only sprites
    const val T_CAVE_BG = 18
    const val T_GEM = 19
    const val T_SEED = 20
    const val T_LAVA2 = 21
    const val T_CRACK = 48
}

enum class Slot { HAT, FACE, BACK, FEET, HAND }

class Item(
    @JvmField val id: Int,
    @JvmField val name: String,
    @JvmField val tile: Int,
    @JvmField val hits: Int = 0,          // punches needed (0 = unbreakable)
    @JvmField val solid: Boolean = false,
    @JvmField val clear: Boolean = false, // draws the cave background behind it
    @JvmField val gemMin: Int = 0,
    @JvmField val gemMax: Int = 0,
    @JvmField val growSec: Int = 0,       // >0 means a seed can be planted
    @JvmField val slot: Slot? = null,
    @JvmField val desc: String = "",
    @JvmField val placeable: Boolean = false
)

object ItemDB {
    private val map = HashMap<Int, Item>()
    private val unknown = Item(-1, "???", 0)
    private fun reg(i: Item) { map[i.id] = i }

    @JvmField val solidTbl = BooleanArray(128)
    @JvmField val clearTbl = BooleanArray(128)

    init {
        reg(Item(Id.DIRT, "Dirt", 1, 3, true, false, 0, 1, 20, desc = "Basic building block.", placeable = true))
        reg(Item(Id.ROCK, "Rock", 2, 5, true, false, 1, 2, 30, desc = "Sturdy stone block.", placeable = true))
        reg(Item(Id.WOOD, "Wood Block", 3, 4, true, false, 1, 2, 30, desc = "Warm wooden planks.", placeable = true))
        reg(Item(Id.BRICK, "Brick", 4, 6, true, false, 2, 3, 45, desc = "Tough red brick.", placeable = true))
        reg(Item(Id.GLASS, "Glass", 5, 4, true, true, 1, 2, 45, desc = "See-through block.", placeable = true))
        reg(Item(Id.GOLD, "Gold Block", 6, 8, true, false, 4, 8, 90, desc = "Shiny and valuable.", placeable = true))
        reg(Item(Id.SAND, "Sand", 7, 3, true, false, 0, 1, 20, desc = "Soft beach sand.", placeable = true))
        reg(Item(Id.LAVA, "Lava", 8, 3, false, false, 0, 0, 0, desc = "Burns! Hold jump to climb out, or you respawn at the main door."))
        reg(Item(Id.BEDROCK, "Bedrock", 9, 0, true, desc = "Unbreakable."))
        reg(Item(Id.PLATFORM, "Platform", 10, 2, false, true, 0, 1, 0, desc = "Jump through it from below.", placeable = true))
        reg(Item(Id.DOOR, "Door", 11, 3, false, true, 0, 0, 0, desc = "Links to another world. Tap to enter, hold to remove.", placeable = true))
        reg(Item(Id.SIGN, "Sign", 12, 3, false, true, 0, 0, 0, desc = "Leave a message. Tap to read, hold to remove.", placeable = true))
        reg(Item(Id.MAIN_DOOR, "Main Door", 13, 0, false, true, desc = "Tap to exit the world."))
        reg(Item(Id.TREE, "Tree", 14, 0, false, true, desc = "A growing tree."))
        reg(Item(Id.WORLD_LOCK, "World Lock", 15, 0, true, false, 0, 0, 0, desc = "Locks the whole world. Only you can build.", placeable = true))
        reg(Item(Id.DIAMOND_LOCK, "Diamond Lock", 16, 0, true, desc = "Locks a world, or worth 100 World Locks.", placeable = true))
        reg(Item(Id.BLUE_LOCK, "Blue Gem Lock", 17, 0, true, desc = "Locks a world, or worth 100 Diamond Locks.", placeable = true))
        reg(Item(Id.WINGS_ANGEL, "Angel Wings", 32, slot = Slot.BACK, desc = "Triple jump + hold jump to glide."))
        reg(Item(Id.WINGS_DEMON, "Demon Wings", 33, slot = Slot.BACK, desc = "Triple jump + hold jump to glide."))
        reg(Item(Id.TOP_HAT, "Top Hat", 34, slot = Slot.HAT, desc = "Very dapper."))
        reg(Item(Id.CROWN, "Golden Crown", 35, slot = Slot.HAT, desc = "Rule your worlds."))
        reg(Item(Id.SHADES, "Sunglasses", 36, slot = Slot.FACE, desc = "Too cool for school."))
        reg(Item(Id.SNEAKERS, "Sneakers", 37, slot = Slot.FEET, desc = "Slightly faster running."))
        reg(Item(Id.PICKAXE, "Golden Pickaxe", 38, slot = Slot.HAND, desc = "Punch twice as hard and faster."))
        reg(Item(Id.T_GEM, "Gem", Id.T_GEM))
        reg(Item(Id.FIST, "Fist", 39, desc = "Punch blocks. Tap empty space to place the selected item."))
        for (b in listOf(Id.DIRT, Id.ROCK, Id.WOOD, Id.BRICK, Id.GLASS, Id.GOLD, Id.SAND)) {
            val base = map[b]!!
            reg(Item(Id.SEED + b, base.name + " Seed", Id.T_SEED, growSec = base.growSec,
                desc = "Plant on solid ground. Grows in ${base.growSec}s.", placeable = true))
        }
        for ((id, it) in map) if (id < 128) { solidTbl[id] = it.solid; clearTbl[id] = it.clear }
    }

    operator fun get(id: Int): Item = map[id] ?: unknown
    fun isSeed(id: Int) = id >= Id.SEED
    fun blockOfSeed(id: Int) = id - Id.SEED
    fun isLock(id: Int) = id == Id.WORLD_LOCK || id == Id.DIAMOND_LOCK || id == Id.BLUE_LOCK
    fun isCurrency(id: Int) = id == Id.WORLD_LOCK || id == Id.DIAMOND_LOCK || id == Id.BLUE_LOCK
}

class Offer(val item: Int, val qty: Int, val price: Int)
class Recipe(val label: String, val need: List<Pair<Int, Int>>, val give: List<Pair<Int, Int>>)

object Shop {
    val tabs: List<Pair<String, List<Offer>>> = listOf(
        "Seeds" to listOf(
            Offer(Id.SEED + Id.DIRT, 3, 5), Offer(Id.SEED + Id.SAND, 3, 5), Offer(Id.SEED + Id.ROCK, 3, 8),
            Offer(Id.SEED + Id.WOOD, 3, 10), Offer(Id.SEED + Id.GLASS, 3, 15), Offer(Id.SEED + Id.BRICK, 3, 15),
            Offer(Id.SEED + Id.GOLD, 3, 60)
        ),
        "Blocks" to listOf(
            Offer(Id.DIRT, 20, 10), Offer(Id.WOOD, 10, 25), Offer(Id.PLATFORM, 5, 15),
            Offer(Id.DOOR, 1, 10), Offer(Id.SIGN, 1, 5)
        ),
        "Locks" to listOf(Offer(Id.WORLD_LOCK, 1, 100), Offer(Id.WORLD_LOCK, 10, 950)),
        "Clothes" to listOf(
            Offer(Id.SHADES, 1, 60), Offer(Id.SNEAKERS, 1, 80), Offer(Id.TOP_HAT, 1, 150),
            Offer(Id.PICKAXE, 1, 400), Offer(Id.WINGS_ANGEL, 1, 800), Offer(Id.WINGS_DEMON, 1, 1200),
            Offer(Id.CROWN, 1, 2500)
        )
    )
    val recipes = listOf(
        Recipe("Brick ×1  ←  3 Rock", listOf(Id.ROCK to 3), listOf(Id.BRICK to 1)),
        Recipe("Glass ×2  ←  3 Dirt", listOf(Id.DIRT to 3), listOf(Id.GLASS to 2)),
        Recipe("Platform ×2  ←  1 Wood Block", listOf(Id.WOOD to 1), listOf(Id.PLATFORM to 2)),
        Recipe("Splice: Gold Seed  ←  Wood Seed + Rock Seed",
            listOf(Id.SEED + Id.WOOD to 1, Id.SEED + Id.ROCK to 1), listOf(Id.SEED + Id.GOLD to 1))
    )
}
