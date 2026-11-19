# Modrinth page is under review

# Beyond the Terrain

See mountains, trees, rivers and coastlines far past your render distance, with a small performance cost. Made for low-end computers

> **Pre-alpha:** expect bugs and issues

## Why this mod was made

- Based on [Far Plane Two continuation](https://github.com/Evga314/Far-Plane-Two-Continuation) by [Evga314](https://github.com/Evga314) which was discontinued and no longer receives updates. Beyond the Horizon continues it with the same idea but kept up to date and built to run on low-end computers

## What it has now

- Distant terrain up to 1024 chunks for now, built by the server using vanilla's world generation noise, so it works with worldgen datapacks like Terralith but will be slower to generate
- Light on performance: about 2–10% cost on an Intel HD 4600
- The distant terrain is saved, so rejoining a world is fast
- Doesn't change your world: it never places blocks or creates chunks
- Optional pixelated filter for a pixel-art look
- Optional approximated trees, chosen by biome (if a biome from a mod or datapack has trees but none show up, please report it)
- Settings for distance, sharpness, memory limit, fog and trees

## What it will add

- Faster terrain generation
- Shader pack support(don't know how long this is gonna take)
- Flashback compatibility(hopefully gonna add this)

## Features currently not gonna be availible in releases

- Block-level detail for explored/unexplored areas probably not gonna add unless i find ways to make the performance impact around 10-20% on 32 chunks

## Multiplayer

The mod must also be installed on the server for the distant terrain to show on the client

## Limitations

The distant terrain is heightmap based, so there are no structures or caves yet(currently reworking to be accurate)

## Disclosure

Full Disclosure AI was used as a tool to rewrite this mod’s code, because the original mod was pretty bugged by me manually patching it. 
All assets/art and texts were made by me, not ai generated.
