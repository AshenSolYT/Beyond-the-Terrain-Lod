# Mod is only available in curseforge and soon in modrinth
https://www.curseforge.com/minecraft/mc-mods/beyond-the-terrain

# Beyond the Terrain

See your terrain well... beyond your terrain with a performance cost that depends on your system

> **Alpha:** expect bugs and issues
> 
> Performance cost depends on your system
> * *On an i5 4590 with an Intel HD 4600 at 64 LOD distance, 2.0 sharpness, 2 finest tiles, 4x4 pixelated filter and Trees on (174 fps off vs 123 fps on, 92 fps with Block Heightmap on at 32 chunks)*
> * *628 fps off vs 408 fps on at 512 LOD distance, 3.0 sharpness and 2 finest tiles (tested by Aadiel on an i7 9700 and an RTX 2060 Super)*
> * *With shader packs 135 fps off vs 123 fps on (tested by Aadiel)*

---

## Why this mod was made

* Based on **[Far Plane Two Continuation](https://github.com/Evga314/Far-Plane-Two-Continuation)** by **[Evga314](https://github.com/Evga314)**, which was discontinued.  Beyond the Terrain continues it with the same idea, kept up to date and built to run on lower end systems.
* Also credit to **[DaPorkchop_](https://github.com/DaMatrix)** for the original **[FarPlaneTwo](https://github.com/PorkStudios/FarPlaneTwo)** mod that's where the idea comes from.

---

## What it has now/Features done

* [x] Heightmap LOD up to 10k chunks for now, built by the server using vanilla's noisemap
* [x] Block Heightmap, which performs better than the old block-level detail
* [x] Faster Heightmap generation
* [x] Better block heightmap detail
* [x] The distant terrain is saved in a cache, so rejoining makes the LOD load fast
* [x] Optional pixelated filter for a pixel-art look
* [x] Optional approximated trees per biome *(being worked on)*
* [x] Shader pack support *(see below)*
* [x] Client and server side settings *(note to change server side settings you have to join a world)*
* since it uses vanilla's noisemap it works with worldgen datapacks like terralith and tectonic

---

## Roadmap

* [ ] Fix trees not showing up in the LOD with shader packs
* [ ] Better block color accuracy
* [ ] Faster Block Heightmap generation
* [ ] Block textures in the LOD
* [ ] Cave generation
* [ ] Trees that match the real tree placement
* [ ] Flashback compatibility *(hopefully)*

---

## Shader packs

Works with Iris shader packs, Sodium and Iris are not required
Also shader pack lighting on LODs isn't perfect

* Tested by Aadiel: Nostalgia, Photon, Solas, Iteration and Rethinking Voxels
* Tested by me: SuperDuperVanilla, Complementary and Vanilla Plus, but the fps was bad because I have an iGPU
* Trees do not show up in the LOD with shader packs *(not fixed yet)*
* The pixelated filter doesn't work with shader packs
* Bliss still has artifacts

---

## Block details

Block Heightmap replaces block details and it performs better, and I'm going to keep working on it until it matches that level of detail
> <sub>Get it?.. Level of Detail... LOD.... oh never mind</sub>

---

## Multiplayer

The mod also has to be installed on the server for the distant terrain to show on the client

---

## Limitations

* The distant terrain is heightmap based, so there are no caves yet *(being worked on)*
* Trees are approximated and don’t use the placement of the actual trees in the world *(being worked on)*

---

## AI Disclosure

Full Disclosure AI was used to rewrite this mods code because the original mod was pretty bugged by me manually patching it.
All assets/art and texts were made by me, not ai generated.
All builds released is manually compiled and tested by me so there's little to no issues.
