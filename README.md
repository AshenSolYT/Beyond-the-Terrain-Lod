# Beyond the Terrain

See your terrain well... beyond your terrain with a performance cost that depends on your system.

> **Alpha:** expect bugs and issues

---

## Why this mod was made

* Based on [Far Plane Two Continuation](https://github.com) by [Evga314](https://github.com), which was discontinued and no longer receives updates. Beyond the Terrain continues it with the same idea, kept up to date and built to run on low-end computers.
* Also credit to [DaPorkchop_](https://github.com) for the original **FarPlaneTwo** mod that this idea comes from.

---

## What it has now

* Distant terrain up to **10k chunks**, built by the server using vanilla's world generation noise, so it works with worldgen datapacks like Terralith but is slower to generate
* Stepped heightmap terrain, which performs better than the old block-level detail
* Performance cost depends on your system *(about 26% on an Intel HD 4600 at 16 render distance, 64 LOD distance, and 4-block stepped terrain — 170 fps off vs 125 fps on)*
* The distant terrain is saved, so rejoining a world is fast
* Doesn't change your world: it never places blocks or creates chunks
* Optional pixelated filter for a pixel-art look
* Optional approximated trees, chosen by biome *(if a biome from a mod or datapack has trees but none show up, please report it)*
* Settings for distance, sharpness, memory limit, fog and trees

---

## Future plans

* [ ] Faster terrain generation
* [ ] Better stepped terrain detail
* [ ] Block textures in the LOD
* [ ] Higher detail when zooming in with a spyglass or a zoom mod
* [ ] Cave generation
* [ ] Trees that match the real tree placement
* [ ] Shader pack support *(idk yet)*
* [ ] Flashback compatibility *(hopefully)*

---

## Block details

Stepped terrain replaces block details and it performs better, and I'm going to keep working on it until it matches that level of detail.  
<sub>Get it? Level of Detail, LOD... oh never mind.</sub>

---

## Multiplayer

The mod also has to be installed on the server for the distant terrain to show on the client.

---

## Limitations

* The distant terrain is heightmap based, so there are no structures or caves yet
* Stepped terrain is an approximation of the real terrain, so overhangs and small details are lost *(being worked on)*
* Terrain colors are still being reworked to be a little more accurate
* Trees are approximated and don’t use the placement of the actual trees in the world *(being worked on)*

---

## Full Disclosure

AI was used as a tool to rewrite this mod’s code, because the original mod was pretty bugged by me manually patching it.  
All assets/art and texts were made by me, not AI generated.
