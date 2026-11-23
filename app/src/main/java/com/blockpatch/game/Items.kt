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
    const val SMALL_LOCK = 56     // locks up to 10 tiles
    const val BIG_LOCK = 57       // locks up to 48 tiles
    const val HUGE_LOCK = 58      // locks up to 200 tiles
    const val BUILDER_LOCK = 59   // locks up to 200 tiles, owner chooses what others may do
    const val WINGS_ANGEL = 32
    const val WINGS_DEMON = 33
    const val TOP_HAT = 34
    const val CROWN = 35
    const val SHADES = 36
    const val SNEAKERS = 37
    const val PICKAXE = 38
    const val FIST = 39
    const val SEED = 1000   // seed id = SEED + block id

    // ---- Adventure Update: water, tools, new blocks
    const val WATER = 60          // special-rendered, non-solid
    const val FISHING_ROD = 61
    const val ICE = 64
    const val CLOUD = 65
    const val CRYSTAL = 66
    const val MUSHROOM = 67
    const val NEON = 68
    const val OBSIDIAN = 69
    const val SANDSTONE = 70
    const val CORAL = 71
    const val VOLCANIC = 72
    const val AMETHYST = 73
    const val RUBY = 74
    const val EMERALD = 75

    // catch / companion items (kept below SEED so they are never treated as seeds)
    const val FISH_SARDINE = 100
    const val FISH_TILAPIA = 101
    const val FISH_TUNA = 102
    const val FISH_KOI = 103
    const val PET_DOG = 110
    const val PET_CAT = 111
    const val PET_DRAGON = 112
    const val PET_SLIME = 113

    // Filipino blocks (block id == atlas tile index, must stay < 64)
    const val BAMBOO = 22       // Kawayan
    const val NIPA = 23         // Pawid / nipa thatch
    const val BANIG = 24        // woven mat
    const val CAPIZ = 25        // capiz shell window
    const val NARRA = 26        // national tree
    const val ADOBE = 27        // Intramuros stone
    const val PAROL = 28        // Christmas star lantern
    const val JEEPNEY = 29      // jeepney panel
    const val SAMPAGUITA = 30   // national flower
    const val TERRACE = 31      // Banaue rice terrace
    const val MANGO = 40
    const val COCONUT = 41      // Niyog
    const val BANANA = 42       // Saging
    const val CALAMANSI = 43
    const val UBE = 44
    const val PALAY = 45        // rice
    const val LANGKA = 46       // jackfruit
    const val PINEAPPLE = 47    // Pinya
    const val SUGARCANE = 52    // Tubo
    const val PAPAYA = 53
    const val PILI = 54
    const val KAMOTE = 55       // sweet potato

    // atlas-only sprites
    const val T_CAVE_BG = 18
    const val T_GEM = 19
    const val T_SEED = 20
    const val T_LAVA2 = 21
    const val T_CRACK = 48
    const val T_WATER = 88
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
    @JvmField val placeable: Boolean = false,
    @JvmField val pet: Boolean = false
)

/** A themed block + its seed. price = gems for a 3-seed pack in the shop. */
class Pinoy(
    val id: Int, val name: String, val hits: Int, val solid: Boolean, val clear: Boolean,
    val gMin: Int, val gMax: Int, val grow: Int, val price: Int, val desc: String
)

val PINOY: List<Pinoy> = listOf(
    // ---- culture / building
    Pinoy(Id.BAMBOO, "Bamboo", 3, true, false, 0, 1, 25, 8, "Kawayan: hollow, strong, and grows fast."),
    Pinoy(Id.NIPA, "Nipa Thatch", 3, true, false, 0, 1, 25, 8, "Pawid roof of the Bahay Kubo."),
    Pinoy(Id.BANIG, "Banig Mat", 3, true, false, 1, 2, 35, 12, "Hand-woven mat in bright colors."),
    Pinoy(Id.CAPIZ, "Capiz Window", 4, true, true, 1, 2, 45, 18, "Windows made of translucent capiz shell."),
    Pinoy(Id.NARRA, "Narra Wood", 5, true, false, 1, 3, 60, 25, "Narra: the national tree of the Philippines."),
    Pinoy(Id.ADOBE, "Adobe Stone", 6, true, false, 2, 3, 60, 25, "Old Spanish-era walls of Intramuros."),
    Pinoy(Id.PAROL, "Parol", 2, false, true, 1, 2, 50, 30, "Glowing Christmas star lantern. Walk through it."),
    Pinoy(Id.JEEPNEY, "Jeepney Panel", 7, true, false, 2, 4, 80, 45, "Shiny, colorful, and always on the road."),
    Pinoy(Id.SAMPAGUITA, "Sampaguita", 2, false, true, 1, 2, 35, 15, "The national flower. Smells wonderful."),
    Pinoy(Id.TERRACE, "Rice Terrace", 4, true, false, 1, 3, 55, 22, "Banaue Rice Terraces carved into the mountain."),
    // ---- crops
    Pinoy(Id.MANGO, "Mango", 3, true, false, 1, 2, 30, 10, "Sweet Philippine mango."),
    Pinoy(Id.COCONUT, "Niyog", 3, true, false, 1, 2, 30, 10, "Coconut: the tree of life."),
    Pinoy(Id.BANANA, "Saging", 3, true, false, 0, 2, 25, 8, "Banana bunch. Plenty of potassium."),
    Pinoy(Id.CALAMANSI, "Calamansi", 3, true, false, 1, 2, 30, 10, "Small, sour, perfect for sawsawan."),
    Pinoy(Id.UBE, "Ube", 3, true, false, 1, 3, 40, 14, "Purple yam. Great for halo-halo."),
    Pinoy(Id.PALAY, "Palay", 3, true, false, 0, 2, 25, 8, "Unhusked rice, the heart of every meal."),
    Pinoy(Id.LANGKA, "Langka", 4, true, false, 1, 3, 45, 15, "Jackfruit: big, spiky, and sweet inside."),
    Pinoy(Id.PINEAPPLE, "Pinya", 3, true, false, 1, 3, 40, 14, "Pineapple. Its fiber makes pina cloth."),
    Pinoy(Id.SUGARCANE, "Tubo", 3, true, false, 0, 2, 30, 9, "Sugarcane stalks."),
    Pinoy(Id.PAPAYA, "Papaya", 3, true, false, 1, 2, 30, 10, "Orange, soft, and sweet."),
    Pinoy(Id.PILI, "Pili Nut", 5, true, false, 2, 4, 70, 30, "Rare nut from Bicol."),
    Pinoy(Id.KAMOTE, "Kamote", 3, true, false, 0, 2, 25, 8, "Sweet potato. Perfect merienda.")
)

/** Adventure Update decorative blocks. */
val DECOR: List<Pinoy> = listOf(
    Pinoy(Id.ICE, "Ice", 3, true, false, 1, 2, 25, 18, "Slippery-looking frost block."),
    Pinoy(Id.CLOUD, "Cloud", 2, false, true, 0, 1, 30, 20, "A soft cloud you can walk through."),
    Pinoy(Id.CRYSTAL, "Crystal", 6, true, false, 2, 4, 70, 35, "A glowing blue crystal cluster."),
    Pinoy(Id.MUSHROOM, "Mushroom", 3, true, false, 1, 2, 35, 15, "A giant spotted mushroom."),
    Pinoy(Id.NEON, "Neon", 4, true, false, 2, 3, 60, 30, "A bright neon grid panel."),
    Pinoy(Id.OBSIDIAN, "Obsidian", 9, true, false, 4, 8, 120, 60, "Volcanic glass. Very tough."),
    Pinoy(Id.SANDSTONE, "Sandstone", 3, true, false, 1, 2, 30, 12, "Layered desert stone."),
    Pinoy(Id.CORAL, "Coral", 2, false, true, 1, 3, 40, 20, "Colorful coral from a warm reef."),
    Pinoy(Id.VOLCANIC, "Volcanic Rock", 7, true, false, 3, 6, 90, 45, "Still warm from the volcano."),
    Pinoy(Id.AMETHYST, "Amethyst", 5, true, false, 3, 5, 85, 40, "A purple gem block."),
    Pinoy(Id.RUBY, "Ruby", 6, true, false, 4, 6, 100, 50, "A deep red gem block."),
    Pinoy(Id.EMERALD, "Emerald", 6, true, false, 4, 6, 100, 50, "A vivid green gem block.")
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
        reg(Item(Id.SMALL_LOCK, "Small Lock", 56, 0, true, false, 0, 0, 0, desc = "Locks up to 10 tiles. Tap to view it, hold to remove.", placeable = true))
        reg(Item(Id.BIG_LOCK, "Big Lock", 57, 0, true, false, 0, 0, 0, desc = "Locks up to 48 tiles. Tap to view it, hold to remove.", placeable = true))
        reg(Item(Id.HUGE_LOCK, "Huge Lock", 58, 0, true, false, 0, 0, 0, desc = "Locks up to 200 tiles. Tap to view it, hold to remove.", placeable = true))
        reg(Item(Id.BUILDER_LOCK, "Builder's Lock", 59, 0, true, false, 0, 0, 0, desc = "Locks up to 200 tiles. Tap it to choose what others may do: build, punch, both, or nothing.", placeable = true))
        reg(Item(Id.WINGS_ANGEL, "Angel Wings", 32, slot = Slot.BACK, desc = "Double jump + hold jump to glide."))
        reg(Item(Id.WINGS_DEMON, "Demon Wings", 33, slot = Slot.BACK, desc = "Double jump + hold jump to glide."))
        reg(Item(Id.TOP_HAT, "Top Hat", 34, slot = Slot.HAT, desc = "Very dapper."))
        reg(Item(Id.CROWN, "Golden Crown", 35, slot = Slot.HAT, desc = "Rule your worlds."))
        reg(Item(Id.SHADES, "Sunglasses", 36, slot = Slot.FACE, desc = "Too cool for school."))
        reg(Item(Id.SNEAKERS, "Sneakers", 37, slot = Slot.FEET, desc = "Slightly faster running."))
        reg(Item(Id.PICKAXE, "Golden Pickaxe", 38, slot = Slot.HAND, desc = "Punch twice as hard and faster."))
        reg(Item(Id.T_GEM, "Gem", Id.T_GEM))
        reg(Item(Id.FIST, "Fist", 39, desc = "Punch blocks. Tap empty space to place the selected item."))

        // ---- Adventure Update
        reg(Item(Id.WATER, "Water", Id.T_WATER, 0, false, false, 0, 0, 0,
            desc = "A pond. Equip a Fishing Rod and tap the water to fish.", placeable = true))
        reg(Item(Id.FISHING_ROD, "Fishing Rod", 60, slot = Slot.HAND,
            desc = "Equip it, then tap water. Tap again when you see the '!' to reel in a fish."))
        for (f in listOf(
            Item(Id.FISH_SARDINE, "Sardine", 76, desc = "A small, common fish. Sell it at the store."),
            Item(Id.FISH_TILAPIA, "Tilapia", 77, desc = "A sturdy pond fish."),
            Item(Id.FISH_TUNA, "Tuna", 78, desc = "A fast, valuable ocean fish."),
            Item(Id.FISH_KOI, "Golden Koi", 79, desc = "A rare, lucky fish. Worth a lot of gems.")
        )) reg(f)
        for (p in listOf(
            Item(Id.PET_DOG, "Puppy", 80, pet = true, desc = "A loyal pup. Follows you and makes you slightly faster."),
            Item(Id.PET_CAT, "Kitten", 81, pet = true, desc = "A lucky kitten. Finds an extra gem in every gem drop."),
            Item(Id.PET_DRAGON, "Baby Dragon", 82, pet = true, desc = "A tiny dragon. Grants an extra mid-air jump."),
            Item(Id.PET_SLIME, "Slime", 83, pet = true, desc = "A friendly slime. Pulls drops toward you from farther away.")
        )) reg(p)

        for (f in PINOY + DECOR) reg(Item(f.id, f.name, f.id, f.hits, f.solid, f.clear, f.gMin, f.gMax, f.grow, desc = f.desc, placeable = true))
        val seedable = listOf(Id.DIRT, Id.ROCK, Id.WOOD, Id.BRICK, Id.GLASS, Id.GOLD, Id.SAND) +
            PINOY.map { it.id } + DECOR.map { it.id }
        for (b in seedable) {
            val base = map[b]!!
            reg(Item(Id.SEED + b, base.name + " Seed", Id.T_SEED, growSec = base.growSec,
                desc = "Plant on solid ground. Grows in ${base.growSec}s.", placeable = true))
        }
        for ((id, it) in map) if (id < 128) { solidTbl[id] = it.solid; clearTbl[id] = it.clear }
    }

    operator fun get(id: Int): Item = map[id] ?: unknown
    fun isSeed(id: Int) = id >= Id.SEED
    fun blockOfSeed(id: Int) = id - Id.SEED
    fun isWorldLock(id: Int) = id == Id.WORLD_LOCK || id == Id.DIAMOND_LOCK || id == Id.BLUE_LOCK
    fun isAreaLock(id: Int) = id == Id.SMALL_LOCK || id == Id.BIG_LOCK || id == Id.HUGE_LOCK || id == Id.BUILDER_LOCK
    fun isLock(id: Int) = isWorldLock(id) || isAreaLock(id)
    fun lockCap(id: Int) = when (id) { Id.SMALL_LOCK -> 10; Id.BIG_LOCK -> 48; Id.HUGE_LOCK, Id.BUILDER_LOCK -> 200; else -> 0 }
    fun isCurrency(id: Int) = id == Id.WORLD_LOCK || id == Id.DIAMOND_LOCK || id == Id.BLUE_LOCK
    fun isFish(id: Int) = id in Id.FISH_SARDINE..Id.FISH_KOI
    fun isPet(id: Int) = id in Id.PET_DOG..Id.PET_SLIME
    fun isRod(id: Int) = id == Id.FISHING_ROD
}

/** What the store pays for one item. */
object Prices {
    fun sell(id: Int): Int = when (id) {
        Id.FISH_SARDINE -> 8
        Id.FISH_TILAPIA -> 18
        Id.FISH_TUNA -> 45
        Id.FISH_KOI -> 120
        Id.PET_DOG -> 100
        Id.PET_CAT -> 150
        Id.PET_DRAGON -> 400
        Id.PET_SLIME -> 250
        Id.FISHING_ROD -> 20
        Id.WATER -> 1
        Id.DIRT, Id.SAND -> 1
        Id.ROCK, Id.WOOD, Id.GLASS -> 2
        Id.BRICK -> 3
        Id.GOLD -> 8
        Id.DIAMOND_LOCK -> 80
        Id.BLUE_LOCK -> 800
        else -> {
            val it = ItemDB[id]
            when {
                ItemDB.isSeed(id) -> maxOf(2, ItemDB[ItemDB.blockOfSeed(id)].gemMin * 2 + 2)
                it.slot != null -> maxOf(20, it.gemMax * 5)
                else -> maxOf(1, it.gemMin + 1)
            }
        }
    }
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
        "Pinoy Seeds" to PINOY.map { Offer(Id.SEED + it.id, 3, it.price) },
        "Pinoy Blocks" to PINOY.filter { it.solid || it.id == Id.PAROL || it.id == Id.SAMPAGUITA }
            .map { Offer(it.id, 10, it.price * 3) },
        "Decor" to (DECOR.map { Offer(it.id, 10, it.price * 3) } + DECOR.map { Offer(Id.SEED + it.id, 3, it.price) }),
        "Fishing" to listOf(
            Offer(Id.FISHING_ROD, 1, 50), Offer(Id.WATER, 10, 15)
        ),
        "Pets" to listOf(
            Offer(Id.PET_DOG, 1, 300), Offer(Id.PET_CAT, 1, 450),
            Offer(Id.PET_SLIME, 1, 750), Offer(Id.PET_DRAGON, 1, 1200)
        ),
        "Locks" to listOf(
            Offer(Id.SMALL_LOCK, 1, 25), Offer(Id.BIG_LOCK, 1, 75), Offer(Id.HUGE_LOCK, 1, 200),
            Offer(Id.BUILDER_LOCK, 1, 250),
            Offer(Id.WORLD_LOCK, 1, 100), Offer(Id.WORLD_LOCK, 10, 950)
        ),
        "Clothes" to listOf(
            Offer(Id.SHADES, 1, 60), Offer(Id.SNEAKERS, 1, 80), Offer(Id.TOP_HAT, 1, 150),
            Offer(Id.PICKAXE, 1, 400), Offer(Id.WINGS_ANGEL, 1, 800), Offer(Id.WINGS_DEMON, 1, 1200),
            Offer(Id.CROWN, 1, 2500)
        )
    )
    val recipes = listOf(
        Recipe("Brick x1  ->  3 Rock", listOf(Id.ROCK to 3), listOf(Id.BRICK to 1)),
        Recipe("Glass x2  ->  3 Dirt", listOf(Id.DIRT to 3), listOf(Id.GLASS to 2)),
        Recipe("Platform x2  ->  1 Wood Block", listOf(Id.WOOD to 1), listOf(Id.PLATFORM to 2)),
        Recipe("Splice: Narra Seed  ->  Wood Seed + Bamboo Seed",
            listOf(Id.SEED + Id.WOOD to 1, Id.SEED + Id.BAMBOO to 1), listOf(Id.SEED + Id.NARRA to 1)),
        Recipe("Splice: Adobe Seed  ->  Brick Seed + Sand Seed",
            listOf(Id.SEED + Id.BRICK to 1, Id.SEED + Id.SAND to 1), listOf(Id.SEED + Id.ADOBE to 1)),
        Recipe("Splice: Parol Seed  ->  Capiz Seed + Bamboo Seed",
            listOf(Id.SEED + Id.CAPIZ to 1, Id.SEED + Id.BAMBOO to 1), listOf(Id.SEED + Id.PAROL to 1)),
        Recipe("Splice: Jeepney Seed  ->  Gold Seed + Rock Seed",
            listOf(Id.SEED + Id.GOLD to 1, Id.SEED + Id.ROCK to 1), listOf(Id.SEED + Id.JEEPNEY to 1)),
        Recipe("Splice: Gold Seed  ->  Wood Seed + Rock Seed",
            listOf(Id.SEED + Id.WOOD to 1, Id.SEED + Id.ROCK to 1), listOf(Id.SEED + Id.GOLD to 1)),
        Recipe("Splice: Ice Seed  ->  Glass Seed + Rock Seed",
            listOf(Id.SEED + Id.GLASS to 1, Id.SEED + Id.ROCK to 1), listOf(Id.SEED + Id.ICE to 1)),
        Recipe("Splice: Crystal Seed  ->  Ice Seed + Gold Seed",
            listOf(Id.SEED + Id.ICE to 1, Id.SEED + Id.GOLD to 1), listOf(Id.SEED + Id.CRYSTAL to 1)),
        Recipe("Splice: Mushroom Seed  ->  Dirt Seed + Wood Seed",
            listOf(Id.SEED + Id.DIRT to 1, Id.SEED + Id.WOOD to 1), listOf(Id.SEED + Id.MUSHROOM to 1)),
        Recipe("Splice: Neon Seed  ->  Crystal Seed + Glass Seed",
            listOf(Id.SEED + Id.CRYSTAL to 1, Id.SEED + Id.GLASS to 1), listOf(Id.SEED + Id.NEON to 1))
    )
}

