# Beyond the Terrain changelogs, 0.0.2 to 0.9.0

All versions and all speed numbers below were tested on an Intel HD 4600 with 4 CPU cores.
Beyond the Terrain was called Beyond the Horizon until the name could not be used on Modrinth. Any version older than 0.0.2 is just a patched version of FarPlane Two Continuation.

# Pre-alpha

## 0.0.2 - 0.0.2.8 (rewrite)
- Rewritten with only height maps
- Square color cells and color blur

## 0.0.3 - 0.0.3.5
- Pixel size, noise and tree billboards
- Pixelated filter (Off/2x2/4x4/8x8)
- Distance set in chunks, plus an LOD memory limit
- Fog color is now vanilla's own
- Typed Distance row

## 0.0.4 - 0.0.4.5
- Depth-only LOD pass before the clouds, which fixes Cirrus cloud transparency
- Exact Terralith tree table
- Horizon timing message
- Trees are hidden inside vanilla's render distance
- Server threads set to cores - 1 (minimum 2)

## 0.0.5
- Distant structure layer

## 0.0.6 - 0.0.6.5
- Block-detail layer with three detail levels (1/2/4-block cells) and distance settings
- Underground detail is off by default
- Debug log and F3 merge counter
- Reworked distant terrain colors using block-detail data

## 0.0.7
- Block-detail layer removed
- Distant terrain colors now learn from the real chunks the client has loaded

## 0.0.7.1
- LOD trees rebuilt as boxes on the block grid, with fewer boxes farther away
- Color survey asks the server for a small tile per unseen biome to improve distant colors

## 0.0.7.2
- F3 cost readout and debug switches (`-Dbth.nodepth`, `-Dbth.nosurvey`, `-Dbth.nolearn`) for finding the fps drop
- Tree overlap fix

## 0.0.7.3
- LOD tree leaves use the game's own leaf colors, so far conifers no longer look black
- Terralith biomes get a ground color guessed from their name
- F3 readout gains planner and pre-pass timers

## 0.0.8 - 0.0.8.1
- Billboard trees: flat tree sprites (small leafy trees and big conifers) with a new Billboard trees setting
- Box trees are now built from the sprite pixels

## 0.0.9
- Distant colors remember up to 6 ground kinds per biome, giving snow, stone and terracotta patches

## 0.0.10 - 0.0.10.8
- Stepped terrain: flat-topped block columns with walls, with a Stepped terrain setting
- Smaller tree crowns, narrower billboard trees, merged flat runs of columns
- New switches for finding fps drops (`-Dbth.noground`, `-Dbth.nowater`, `-Dbth.nopixel`, `-Dbth.norender`, `-Dbth.noclear`)
- Tree sprites resized to 7x7 (small) and 15x15 (big)
- Pixel filter skipped on far tiles where it can't be seen
- Depth pass only draws tiles above the camera: 114 fps to 136 fps in one test
- Color survey no longer depends on the detail setting, and works per biome and slope
- Trees and structures no longer pop in and out by distance
- Fewer draw calls on Intel HD graphics, with merged walls

## 0.0.11 - 0.0.11.1
- Distant terrain colors now come from the server's own surface rules
- Color learner and survey removed

# Alpha

## 0.1.0 - 0.1.1
- 1-block stepped terrain and a finer finest tile
- No log spam when idle
- Fixed hangs when closing the game or rejoining a world

## 0.2.0
- Fine tiles now take their heights from the coarser tile, so they generate faster

## 0.3.0 - 0.4.1
- No notes kept for these versions

## 0.5.0
- Faster far terrain generation
- Fine tiles use real chunks, so villages and builds show up in the distant terrain with more accurate block colors
- Detail settings removed

## 0.5.1 - 0.5.3 (withdrawn)
- Loading and generation became much slower, so these were dropped

## 0.5.4 - 0.5.5
- Rebuilt from 0.5.0
- New "Structures in terrain" server setting
- Faster meshing of stepped terrain

## 0.6.0
- Stepped terrain at 1-block spacing uses each block's own height and color
- Darker cliff walls
- Faster loading and generation

## 0.6.1
- Zoom fix: only tiles inside your view get zoom detail, so zooming no longer drops fps to single digits

## 0.7.0
- Removed the old distant structure layer
- New "Structures and player-made builds" setting with its own quality levels
- Zoom detail capped at 32x

## 0.8.0 - 0.8.10
- First Iris shader pack support
- Fixed missing colors on the distant terrain under shader packs
- With a shader pack on, distant trees and the pixel filter are turned off

## 0.8.11 - 0.8.13
- Settings renamed: "Distant terrain" is now "Heightmap LOD" and "Stepped terrain" is now "Block Heightmap"
- Reworked fog: no square line where normal chunks meet the distant terrain, and no jump when the fog changes
- Trees placed by the server using Minecraft's own tree placement (not carried into later versions)
- Faster generation of distant terrain

## 0.8.14 - 0.8.15.1 (withdrawn)
- These versions made structures stop appearing in the distant terrain, so they were dropped. 0.8.16 replaces them

## 0.8.16 - 0.8.16.1
- Block Heightmap and Trees settings apply right away, without rejoining the world
- Block Heightmap and Trees are now simple on/off options, each with its own distance
- Removed the billboard trees setting
- Trees on Block Heightmap terrain sit on the ground instead of floating
- Shader packs: distant terrain is dimmer so it blends better with normal terrain

## 0.8.17 - 0.8.17.2
- Block Heightmap and tree distances are now shown in chunks
- Fixed Block Heightmap distance only working at very high sharpness
- Fixed missing trees at medium distance
- Shader packs: distant terrain covers normal terrain less where they meet
- New option to turn off the raised far distance for shader packs (`-Dbth.irisfar=off`), useful if Bliss shows dark walls
- Removed zoom support (it caused stutters)

## 0.8.18 - 0.8.18.8
- Shader packs: the distant terrain stops where normal chunks start, and packs read its distance correctly
- Block Heightmap has its own very fine detail close to you, no matter the Sharpness setting
- Much faster terrain generation: 1028 tiles in 38 seconds instead of about 134 seconds
- Distant terrain loads faster
- Fixed flat ground in forests

## 0.8.19 - 0.9.0
- Smaller tile files, so less data is saved and sent
- Block Heightmap and tree distances adjust automatically to stay inside the memory limit, which stops tiles loading and unloading over and over
- Much faster loading: a 256 chunk view with Block Heightmap at 128 chunks went from 217 seconds to 42 seconds
- Planning what to load runs on its own thread, which reduces stutter
- Less memory churn while building Block Heightmap terrain
- Fixed loading stalling for 40 seconds or more while the game was paused in singleplayer
