# Generates original 16x16 pixel-art tiles appended to the existing atlas.
# The first 64 tiles (the project's original art) are preserved untouched.
Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$img = Join-Path $root "app\src\main\assets\img\tiles.png"
$bak = Join-Path $PSScriptRoot "tiles-base.png"
if (-not (Test-Path $bak)) { Copy-Item $img $bak }

$src = [System.Drawing.Bitmap]::FromFile($img)
$dst = New-Object System.Drawing.Bitmap 256, 96, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($dst)
$g.Clear([System.Drawing.Color]::Transparent)
$g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::NearestNeighbor
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::Half
$g.DrawImage($src, [System.Drawing.Rectangle]::new(0, 0, 256, 64), 0, 0, 256, 64, [System.Drawing.GraphicsUnit]::Pixel)
$g.Dispose()
$src.Dispose()

$script:ox = 0
$script:oy = 0
function Col($a, $r, $g, $b) { [System.Drawing.Color]::FromArgb($a, $r, $g, $b) }
function Tile($i) { $script:ox = ($i % 16) * 16; $script:oy = [math]::Floor($i / 16) * 16 }
function Px($x, $y, $c) {
    if ($x -ge 0 -and $x -lt 16 -and $y -ge 0 -and $y -lt 16) {
        $dst.SetPixel($script:ox + [int]$x, $script:oy + [int]$y, $c)
    }
}
function Rect($x0, $y0, $x1, $y1, $c) {
    for ($y = $y0; $y -le $y1; $y++) { for ($x = $x0; $x -le $x1; $x++) { Px $x $y $c } }
}
function Line($x0, $y0, $x1, $y1, $c) {
    $dx = [math]::Abs($x1 - $x0); $sx = if ($x0 -lt $x1) { 1 } else { -1 }
    $dy = -[math]::Abs($y1 - $y0); $sy = if ($y0 -lt $y1) { 1 } else { -1 }
    $err = $dx + $dy
    while ($true) {
        Px $x0 $y0 $c
        if ($x0 -eq $x1 -and $y0 -eq $y1) { break }
        $e2 = 2 * $err
        if ($e2 -ge $dy) { $err += $dy; $x0 += $sx }
        if ($e2 -le $dx) { $err += $dx; $y0 += $sy }
    }
}
function Disc($cx, $cy, $r, $c) {
    for ($y = -$r; $y -le $r; $y++) { for ($x = -$r; $x -le $r; $x++) { if ($x * $x + $y * $y -le $r * $r) { Px ($cx + $x) ($cy + $y) $c } } }
}
function Ellipse($cx, $cy, $rx, $ry, $c) {
    for ($y = -$ry; $y -le $ry; $y++) {
        for ($x = -$rx; $x -le $rx; $x++) {
            if (($x * $x * $ry * $ry + $y * $y * $rx * $rx) -le ($rx * $rx * $ry * $ry)) { Px ($cx + $x) ($cy + $y) $c }
        }
    }
}

# 60: fishing rod
Tile 60
$wood = Col 255 122 82 42; $wood2 = Col 255 168 118 66
Line 4 13 12 4 $wood; Line 5 13 13 4 $wood2
Px 13 4 (Col 255 240 240 240); Px 14 4 (Col 255 210 210 210); Px 14 5 (Col 255 210 210 210)
Px 14 6 (Col 255 220 60 60); Px 15 6 (Col 255 220 60 60); Px 15 7 (Col 255 160 40 40)

# 64: ice
Tile 64
$ice = Col 255 178 224 246; $ice2 = Col 255 214 241 255; $ice3 = Col 255 120 176 214
Rect 0 0 15 15 $ice
Rect 0 0 15 1 $ice2; Rect 0 2 2 15 $ice3; Rect 13 2 15 15 $ice3
Line 3 4 6 9 $ice2; Line 7 3 10 7 $ice2; Line 4 11 9 12 $ice2; Line 11 9 13 13 $ice2

# 65: cloud
Tile 65
$cl = Col 235 255 255 255; $cl2 = Col 235 224 238 250
Ellipse 5 10 5 3 $cl; Ellipse 10 9 5 4 $cl; Ellipse 8 6 4 3 $cl
Ellipse 5 12 4 2 $cl2; Ellipse 11 12 4 2 $cl2

# 66: crystal
Tile 66
$cy = Col 255 96 226 240; $cy2 = Col 255 190 252 255; $cy3 = Col 255 44 150 190
Line 8 1 13 8 $cy2; Line 8 1 3 8 $cy2; Line 3 8 8 15 $cy3; Line 13 8 8 15 $cy3
Rect 5 5 10 10 $cy; Line 8 2 8 14 $cy2; Line 4 8 12 8 $cy2

# 67: mushroom
Tile 67
$cap = Col 255 206 56 62; $cap2 = Col 255 240 96 96; $stem = Col 255 238 214 172
Ellipse 8 7 7 5 $cap; Rect 0 8 15 8 $cap
Ellipse 8 5 5 3 $cap2; Disc 5 6 1 (Col 255 255 240 230); Disc 10 5 1 (Col 255 255 240 230); Disc 8 8 1 (Col 255 255 240 230)
Rect 6 9 9 14 $stem; Rect 5 14 10 14 $stem

# 68: neon
Tile 68
Rect 0 0 15 15 (Col 255 20 10 34)
Rect 0 0 15 0 (Col 255 255 54 200); Rect 0 15 15 15 (Col 255 54 240 255)
Rect 0 0 0 15 (Col 255 255 54 200); Rect 15 0 15 15 (Col 255 54 240 255)
Line 2 13 13 2 (Col 255 255 54 200); Line 4 14 14 4 (Col 255 54 240 255); Line 2 5 5 2 (Col 255 255 255 255)

# 69: obsidian
Tile 69
Rect 0 0 15 15 (Col 255 34 28 48)
for ($y = 0; $y -lt 16; $y++) { for ($x = 0; $x -lt 16; $x++) { if ((($x * 7 + $y * 13) % 11) -eq 0) { Px $x $y (Col 255 62 50 88) } } }
Line 2 12 6 5 (Col 255 96 78 130); Line 9 14 13 8 (Col 255 96 78 130); Disc 4 4 1 (Col 255 130 110 170)

# 70: sandstone
Tile 70
$sand = Col 255 214 182 118; $sand2 = Col 255 188 150 92
Rect 0 0 15 15 $sand
Rect 0 2 15 3 $sand2; Rect 0 7 15 7 $sand2; Rect 0 11 15 12 $sand2
Rect 0 0 15 0 (Col 255 232 208 150); Px 3 5 $sand2; Px 11 9 $sand2; Px 6 14 $sand2

# 71: coral
Tile 71
$co = Col 255 255 112 138; $co2 = Col 255 255 176 84
Line 8 15 8 7 $co; Line 8 10 4 6 $co; Line 8 12 12 8 $co; Line 4 6 4 3 $co; Line 12 8 12 4 $co
Line 8 7 6 4 $co2; Line 8 9 9 5 $co2; Disc 4 3 1 $co2; Disc 12 4 1 $co2; Disc 6 4 1 $co2

# 72: volcanic
Tile 72
Rect 0 0 15 15 (Col 255 52 48 52)
for ($y = 0; $y -lt 16; $y++) { for ($x = 0; $x -lt 16; $x++) { if ((($x * 5 + $y * 3) % 7) -eq 0) { Px $x $y (Col 255 74 68 76) } } }
Line 1 4 6 8 (Col 255 255 112 32); Line 6 8 5 13 (Col 255 255 112 32); Line 9 2 11 7 (Col 255 255 160 48); Line 11 7 14 7 (Col 255 255 112 32)

# 73: amethyst
Tile 73
$am = Col 255 156 88 220; $am2 = Col 255 212 160 255; $am3 = Col 255 100 48 156
Line 8 1 13 8 $am2; Line 8 1 3 8 $am2; Line 3 8 8 15 $am3; Line 13 8 8 15 $am3; Rect 5 5 10 10 $am; Line 8 2 8 14 $am2

# 74: ruby
Tile 74
$ru = Col 255 226 48 72; $ru2 = Col 255 255 140 150; $ru3 = Col 255 150 24 48
Line 8 1 13 8 $ru2; Line 8 1 3 8 $ru2; Line 3 8 8 15 $ru3; Line 13 8 8 15 $ru3; Rect 5 5 10 10 $ru; Line 8 2 8 14 $ru2

# 75: emerald
Tile 75
$em = Col 255 48 208 112; $em2 = Col 255 150 255 190; $em3 = Col 255 24 132 72
Line 8 1 13 8 $em2; Line 8 1 3 8 $em2; Line 3 8 8 15 $em3; Line 13 8 8 15 $em3; Rect 5 5 10 10 $em; Line 8 2 8 14 $em2

function Fish($i, $body, $light, $dark, $fin) {
    Tile $i
    Ellipse 7 8 5 3 $body
    Ellipse 7 7 4 1 $light
    Px 2 8 (Col 0 0 0 0); Px 1 7 $fin; Px 1 9 $fin; Px 2 6 $fin; Px 2 10 $fin
    Px 11 7 (Col 255 255 255 255); Px 12 7 (Col 255 20 20 30)
    Line 4 5 6 3 $fin; Line 6 11 8 13 $fin
    for ($x = 4; $x -le 9; $x++) { if (($x % 2) -eq 0) { Px $x 10 $dark } }
}
Fish 76 (Col 255 112 170 210) (Col 255 190 230 250) (Col 255 60 110 160) (Col 255 90 150 200)
Fish 77 (Col 255 158 176 158) (Col 255 220 230 210) (Col 255 110 130 110) (Col 255 140 170 140)
Fish 78 (Col 255 52 84 150) (Col 255 120 160 220) (Col 255 32 52 110) (Col 255 240 200 70)
Fish 79 (Col 255 240 132 48) (Col 255 255 210 170) (Col 255 190 80 32) (Col 255 255 240 230)

# 80: dog
Tile 80
$dog = Col 255 170 116 70; $dog2 = Col 255 210 158 100; $dark = Col 255 52 36 28
Rect 3 5 12 11 $dog; Rect 4 4 11 5 $dog2
Rect 2 5 3 9 $dog; Rect 12 5 13 9 $dog
Px 5 8 (Col 255 30 22 18); Px 10 8 (Col 255 30 22 18); Rect 7 9 8 10 $dark; Rect 6 12 9 13 (Col 255 236 200 160)
Rect 2 11 4 13 $dog2; Rect 11 11 13 13 $dog2

# 81: cat
Tile 81
$cat = Col 255 150 152 168; $cat2 = Col 255 196 198 214; $dark = Col 255 40 40 52
Rect 3 5 12 12 $cat; Rect 4 4 11 5 $cat2
Line 3 5 4 1 $cat; Line 4 1 6 4 $cat; Line 12 5 11 1 $cat; Line 11 1 9 4 $cat
Px 5 8 (Col 255 60 220 120); Px 10 8 (Col 255 60 220 120); Rect 7 10 8 10 (Col 255 236 150 160)
Line 1 10 5 10 (Col 255 210 210 220); Line 1 12 5 12 (Col 255 210 210 220); Line 10 10 14 10 (Col 255 210 210 220); Line 10 12 14 12 (Col 255 210 210 220)

# 82: dragon
Tile 82
$dr = Col 255 74 180 96; $dr2 = Col 255 140 230 130; $dark = Col 255 28 70 40
Rect 4 5 11 12 $dr; Rect 5 4 10 5 $dr2
Line 3 5 1 1 (Col 255 230 220 160); Line 1 1 4 3 (Col 255 230 220 160)
Line 12 5 14 1 (Col 255 230 220 160); Line 14 1 11 3 (Col 255 230 220 160)
Px 6 8 (Col 255 255 230 60); Px 9 8 (Col 255 255 230 60)
Rect 6 10 9 12 $dark; Rect 5 12 10 13 (Col 255 60 120 70)
Px 4 11 (Col 255 255 255 255); Px 5 11 (Col 255 255 255 255); Px 10 11 (Col 255 255 255 255); Px 11 11 (Col 255 255 255 255)

# 83: slime
Tile 83
$sl = Col 255 96 220 96; $sl2 = Col 255 170 255 150
Ellipse 8 9 6 5 $sl; Rect 2 9 14 13 $sl
Ellipse 6 6 3 2 $sl2
Px 5 8 (Col 255 20 40 24); Px 10 8 (Col 255 20 40 24); Line 6 11 9 11 (Col 255 20 70 30)
Px 2 12 (Col 140 255 255 255); Px 13 11 (Col 140 255 255 255)

# 84: heart
Tile 84
$hr = Col 255 235 70 100; Ellipse 5 5 3 2 $hr; Ellipse 10 5 3 2 $hr; Rect 2 5 13 7 $hr
Line 2 8 7 14 $hr; Line 13 8 8 14 $hr; Rect 4 8 11 10 $hr
Px 4 4 (Col 255 255 170 190); Px 5 4 (Col 255 255 170 190)

# 85: star
Tile 85
$st = Col 255 250 214 70; Line 8 1 8 14 $st; Line 1 8 14 8 $st; Line 3 3 12 12 $st; Line 12 3 3 12 $st
Disc 8 8 2 (Col 255 255 245 170)

# 86: coin
Tile 86
$co = Col 255 246 196 52; Disc 8 8 6 $co; Disc 8 8 4 (Col 255 255 224 120)
Rect 7 5 8 11 (Col 255 180 120 20); Px 6 6 (Col 255 255 255 220)

# 87: music note
Tile 87
$nt = Col 255 230 100 230; Ellipse 5 12 3 2 $nt; Rect 8 3 9 12 $nt; Rect 8 3 13 4 $nt; Rect 13 4 14 7 $nt

# 88: water droplet (item icon; the world tile is drawn procedurally)
Tile 88
$wt = Col 255 74 164 224; $wt2 = Col 255 170 226 255
Line 8 1 8 2 $wt
Line 8 1 5 6 $wt; Line 8 1 11 6 $wt
Ellipse 8 10 5 5 $wt; Rect 4 8 12 14 $wt
Ellipse 7 9 2 2 $wt2; Px 6 8 $wt2

$dst.Save($img, [System.Drawing.Imaging.ImageFormat]::Png)
$dst.Dispose()
Write-Output "Atlas written: $img (256x96)"
