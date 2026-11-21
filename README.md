# Beyond the Terrain

See your terrain well... beyond your terrain with a performance cost that depends on your system

> **Alpha:** expect bugs and issues
> 
> Performance cost depends on your system *(about 26% on an Intel HD 4600 at 16 render distance, 64 LOD distance, 2 finest tiles and 4 blocks stepped terrain — 170 fps off vs 125 fps on)*

---

## Why this mod was made

* Based on **[Far Plane Two Continuation](https://github.com/Evga314/Far-Plane-Two-Continuation)** by **[Evga314](https://github.com/Evga314)**, which was discontinued.  Beyond the Terrain continues it with the same idea, kept up to date and built to run on lower end systems.
* Also credit to **[DaPorkchop_](https://github.com/DaMatrix)** for the original **[FarPlaneTwo](https://github.com/PorkStudios/FarPlaneTwo)** mod that's where the idea comes from.

---

## What it has now/Features done

* [x] Heightmap terrain up to 10k chunks for now, built by the server using vanilla's noisemap,   
* [x] Stepped heightmap terrain, which performs better than the old block-level detail
* [x] The distant terrain is saved in a cache, so rejoining makes the LOD load fast
* [x] Optional pixelated filter for a pixel-art look
* [x] Optional approximated trees per biome *(being worked on)*
* [x] Client and server side settings *(note to change server side settings you have to join a world)*
* since it uses vanilla's noisemap it works with worldgen datapacks like terralith and tectonic

---

## Roadmap

* [x] Faster Heightmap generation
* [x] Better stepped terrain detail
* [x] Higher detail when zooming in with a spyglass or a zoom mod
* [x] Terrain colors need to be reworked to be a little more accurate
* [x] Shader pack support "Only internal developement versions will be available soon*
* [ ] Faster Stepped Heightmap generation
* [ ] Block textures in the LOD
* [ ] Cave generation
* [ ] Trees that match the real tree placement
* [ ] Flashback compatibility *(hopefully)*

---

## Block details

Stepped terrain replaces block details and it performs better, and I'm going to keep working on it until it matches that level of detail
> <sub>Get it?.. Level of Detail... LOD.... oh never mind</sub>

---

## Multiplayer

The mod also has to be installed on the server for the distant terrain to show on the client

---

## Limitations

* The distant and stepped terrain and is heightmap based, so there are no structures or caves yet *(being worked on)*
* Trees are approximated and don’t use the placement of the actual trees in the world *(being worked on)*

---

## AI Disclosure

Full Disclosure AI was used to rewrite this mods code because the original mod was pretty bugged by me manually patching it.
All assets/art and texts were made by me, not ai generated.
All builds released is manually compiled and tested by me so there's no issues.
