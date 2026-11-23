Blockpatch (native Android, Kotlin)
===================================

Build with Android Studio, or from a terminal:
  gradle assembleDebug
  (Gradle 8.7+/9.x, JDK 17+, Android SDK platform 37)

Install:
  adb install -r app/build/outputs/apk/debug/app-debug.apk

The project intentionally uses its own code, art and audio. It does not
include or convert assets from any commercial game.

------------------------------------------------------------
WHAT'S IN THE GAME
------------------------------------------------------------
Core sandbox
  * Punch, build and break blocks in 100 x 60 worlds (Normal / Hills / Blank)
  * Growtopia-style punching: punch cooldown, directional punch animation,
    forgiving one-block-gap collision when jumping into caves
  * Runs at a fixed 60 FPS (no FPS settings or FPS overlay)
  * Seeds, growth timers, harvesting, gem drops, XP and levels
  * Doors, signs, main door, world locks, area locks (Small / Big / Huge /
    Builder's), world ownership and lock permissions
  * Backpack, quick bar, clothing slots, wings, pickaxe, shop, crafting
  * Chat with emotes and commands

Adventure Update
  * FISHING: generated ponds + placeable Water. Buy a Fishing Rod
    (Store > Fishing), equip it, tap water, then tap again on "!" to reel in
    a Sardine, Tilapia, Tuna or Golden Koi.
  * PETS: Puppy (+speed), Kitten (+1 gem per gem drop), Baby Dragon
    (+1 mid-air jump) and Slime (bigger pickup magnet). Summon from the
    backpack; pets follow you.
  * BADGES: 15 permanent achievements with automatic gem rewards.
  * DAILY QUESTS: three deterministic quests per day with claimable rewards.
  * SELL TAB: sell fish, crops, gems and spare blocks for gems.
  * SKY & WEATHER: automatic day/night cycle with sun, moon and stars;
    rain and snow weather. Change it in Menu > Sky & Weather or with
    /time and /weather.
  * 12 new decorative blocks (Ice, Cloud, Crystal, Mushroom, Neon, Obsidian,
    Sandstone, Coral, Volcanic Rock, Amethyst, Ruby, Emerald), their seeds,
    four new splice recipes, and underground ore generation.

Chat commands
  /help /wave /dance /laugh /warp <world> /clear /exit
  /time <auto|day|sunset|night>
  /weather <auto|clear|rain|snow>
  /badges /quests /stats /pet /fish

------------------------------------------------------------
PROJECT LAYOUT
------------------------------------------------------------
  app/src/main/java/com/blockpatch/game/
    GameView.kt   game loop, physics, rendering, interaction
    World.kt      world data, generation, save/load
    Items.kt      item/block definitions, shop, recipes, sell prices
    Profile.kt    persistence, inventory, badges, daily quests
    Meta.kt       badge and daily-quest definitions
    GameActivity.kt / LobbyActivity.kt / Ui.kt
    Atlas.kt      sprite atlas + icon rendering

  app/src/main/assets/img/tiles.png   256x96 sprite atlas (original art)
  tools/make_atlas.ps1                regenerates the 32 appended tiles
  app/src/main/assets/snd/*.wav       sound effects and music loop

Worlds are saved in the app's private storage; player data in
SharedPreferences. Online co-op is not included (it needs a server).
