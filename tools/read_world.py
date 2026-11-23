"""Parse a Blockpatch .bpw world file and report its contents.

Format (DataOutputStream, big-endian, writeUTF = modified UTF-8):
  int magic, byte type, utf ownerId, utf ownerName,
  int lockX, lockY, doorX, doorY, long created,
  byte[W*H] fg, byte[W*H] bg,
  int treeCount, (int key, int seed, long planted)*
  int linkCount, (int key, utf world)*
  int textCount, (int key, utf text)*
  int areaCount, (int x, int y, int type, utf ownerId, utf ownerName, int mode, int n, int[n] tiles)*
"""
import struct
import sys
from collections import Counter

W, H = 100, 60
MAGIC = 0x42505733
NAMES = {
    0: "AIR", 1: "DIRT", 2: "ROCK", 3: "WOOD", 4: "BRICK", 5: "GLASS", 6: "GOLD",
    7: "SAND", 8: "LAVA", 9: "BEDROCK", 10: "PLATFORM", 11: "DOOR", 12: "SIGN",
    13: "MAIN_DOOR", 14: "TREE", 15: "WORLD_LOCK", 16: "DIAMOND_LOCK", 17: "BLUE_LOCK",
    18: "BG_CAVE", 19: "GEM", 20: "SEED", 21: "LAVA2",
    22: "BAMBOO", 23: "NIPA", 24: "BANIG", 25: "CAPIZ", 26: "NARRA", 27: "ADOBE",
    28: "PAROL", 29: "JEEPNEY", 30: "SAMPAGUITA", 31: "TERRACE",
    32: "WINGS_ANGEL", 33: "WINGS_DEMON", 34: "TOP_HAT", 35: "CROWN", 36: "SHADES",
    37: "SNEAKERS", 38: "PICKAXE", 39: "FIST",
    40: "MANGO", 41: "COCONUT", 42: "BANANA", 43: "CALAMANSI", 44: "UBE", 45: "PALAY",
    46: "LANGKA", 47: "PINEAPPLE", 52: "SUGARCANE", 53: "PAPAYA", 54: "PILI", 55: "KAMOTE",
    56: "SMALL_LOCK", 57: "BIG_LOCK", 58: "HUGE_LOCK", 59: "BUILDER_LOCK",
    60: "WATER", 61: "FISHING_ROD",
    64: "ICE", 65: "CLOUD", 66: "CRYSTAL", 67: "MUSHROOM", 68: "NEON", 69: "OBSIDIAN",
    70: "SANDSTONE", 71: "CORAL", 72: "VOLCANIC", 73: "AMETHYST", 74: "RUBY", 75: "EMERALD",
}


class R:
    def __init__(self, b):
        self.b = b
        self.i = 0

    def take(self, n):
        v = self.b[self.i:self.i + n]
        self.i += n
        return v

    def i32(self):
        return struct.unpack(">i", self.take(4))[0]

    def i64(self):
        return struct.unpack(">q", self.take(8))[0]

    def u8(self):
        return self.take(1)[0]

    def utf(self):
        n = struct.unpack(">H", self.take(2))[0]
        raw = self.take(n)
        # DataOutputStream writes modified UTF-8; the game only stores ASCII-ish text
        return raw.decode("utf-8", "replace")


def main(path):
    data = open(path, "rb").read()
    if data[:2] == b"\x1f\x8b":
        import gzip
        data = gzip.decompress(data)
    r = R(data)
    magic = r.i32()
    assert magic == MAGIC, f"bad magic {magic:#x}"
    typ = r.u8()
    owner = r.utf()
    owner_name = r.utf()
    lock_x, lock_y, door_x, door_y = r.i32(), r.i32(), r.i32(), r.i32()
    created = r.i64()
    fg = r.take(W * H)
    bg = r.take(W * H)

    trees = {}
    for _ in range(r.i32()):
        k = r.i32()
        trees[k] = (r.i32(), r.i64())
    links = {}
    for _ in range(r.i32()):
        links[r.i32()] = r.utf()
    texts = {}
    for _ in range(r.i32()):
        texts[r.i32()] = r.utf()
    areas = []
    for _ in range(r.i32()):
        ax, ay, at = r.i32(), r.i32(), r.i32()
        aoid, aon = r.utf(), r.utf()
        mode = r.i32()
        n = r.i32()
        tiles = [r.i32() for _ in range(n)]
        areas.append((ax, ay, at, aon, mode, len(tiles)))

    print(f"file            {path} ({len(data)} bytes)")
    print(f"type            {typ} ({['Normal', 'Hills', 'Blank'][typ] if typ < 3 else '?'})")
    print(f"owner           {owner_name or '-'} ({owner or '-'})")
    print(f"door            {door_x},{door_y}   lock {lock_x},{lock_y}")
    print(f"trees/links/texts/areas  {len(trees)} / {len(links)} / {len(texts)} / {len(areas)}")
    counts = Counter(fg)
    total = sum(counts.values())
    print(f"fg tiles        {total} (expected {W * H})")
    print("top tiles:")
    for tid, n in counts.most_common(14):
        print(f"   {NAMES.get(tid, 'ID' + str(tid)):12s} {n:5d}  {100.0 * n / total:5.1f}%")
    water = counts.get(60, 0)
    print(f"water tiles     {water}")
    ores = {k: counts.get(k, 0) for k in (66, 69, 73, 74, 75, 6)}
    print("ore tiles       " + "  ".join(f"{NAMES[k]}={v}" for k, v in ores.items()))
    rows = []
    for y in range(H):
        row = fg[y * W:(y + 1) * W]
        if 60 in row:
            xs = [x for x, v in enumerate(row) if v == 60]
            rows.append((y, min(xs), max(xs), len(xs)))
    print(f"pond rows       {len(rows)}")
    for y, x0, x1, n in rows[:6]:
        print(f"   y={y:2d}  x={x0}..{x1}  ({n} tiles)")
    if areas:
        print("area locks:")
        for ax, ay, at, aon, mode, n in areas:
            print(f"   {NAMES.get(at, at)} at {ax},{ay} by {aon} mode={mode} tiles={n}")


if __name__ == "__main__":
    main(sys.argv[1])