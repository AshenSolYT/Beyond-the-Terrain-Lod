#version 330

#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

#ifdef PIXEL_COLOR
#define NOISE
#endif

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
layout(location = 6) in vec3 viewPos;
layout(location = 7) in vec2 groundPos;
#ifdef PIXEL_COLOR
layout(location = 3) in vec2 localXZ;
#endif
#ifdef NOISE
layout(location = 8) in vec3 noisePos;
#endif
#ifdef SPRITES
layout(location = 3) in vec3 spriteInfo;
layout(location = 4) in vec3 spriteLight;
layout(location = 5) in vec2 relativeXZ;
#endif

layout(location = 0) out vec4 fragColor;

#ifdef SPRITES
// The tree sprites, two bits a pixel from the left, row 0 on top: 0 nothing, 1 leaves (tinted), 2 trunk.
const uint SPRITE_SMALL[7] = uint[7](0x00000000u, 0x00000150u, 0x00000150u, 0x00000554u, 0x00000554u, 0x00000080u, 0x00000080u);
const uint SPRITE_BIG[15] = uint[15](0x00000000u, 0x00004000u, 0x00015000u, 0x00008000u, 0x00015000u, 0x00055400u, 0x00015000u, 0x00055400u, 0x00155500u, 0x00055400u, 0x00055400u, 0x00155500u, 0x00008000u, 0x00008000u, 0x00008000u);
const uint SPRITE_BIG2[15] = uint[15](0x00004000u, 0x00015000u, 0x00008000u, 0x00055400u, 0x00015000u, 0x00155500u, 0x00055400u, 0x00155500u, 0x00008000u, 0x00008000u, 0x00008000u, 0x00008000u, 0x00008000u, 0x00008000u, 0x00008000u);

int spritePixel(int id, vec2 uv) {
    int size = id == 0 ? 7 : 15;
    int x = clamp(int(uv.x * float(size)), 0, size - 1);
    int y = clamp(size - 1 - int(uv.y * float(size)), 0, size - 1);
    uint row = id == 0 ? SPRITE_SMALL[y] : id == 1 ? SPRITE_BIG[y] : SPRITE_BIG2[y];
    return int((row >> uint(x * 2)) & 3u);
}
#endif

#ifdef STEPPED
// A light from the east, south and above, and how much of the light the unlit side keeps. The tops of the terrain come out as 1.
const vec3 SUN = normalize(vec3(0.45, 0.80, 0.40));
const float AMBIENT = 0.50;
// How dark the mesh builder made the walls against the tops, by the side they face (MeshBuilder: WALL_EAST_WEST, WALL_NORTH_SOUTH).
const float BAKED_EAST_WEST = 0.70;
const float BAKED_NORTH_SOUTH = 0.54;

// The light on a surface of the world-space normal n, against the light on a flat top.
float surfaceLight(vec3 n) {
    return AMBIENT + (1.0 - AMBIENT) * max(dot(n, SUN), 0.0) / SUN.y;
}
#endif

#ifdef NOISE
float cellHash(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
#endif

void main() {
#ifdef DEPTH_ONLY
    // Nothing of the color: only the depth is written, with the blend leaving what is there.
    fragColor = vec4(0.0);
    return;
#endif
    // The environmental fog is always measured by the 3D distance. The render-distance fog is too with this mod's own fog (ColorModulator.w 1:
    // vanilla's terrain has it in the environmental one then, and the two fogs are the same sphere); with vanilla's own, or another mod's,
    // it is the distance on the ground plane (w 0), as it is on vanilla's terrain, so the fog does not change along the edge of vanilla's chunks.
    float renderDistance = ColorModulator.w > 0.5 ? sphericalVertexDistance : cylindricalVertexDistance;
    vec4 smoothColor = vertexColor;
#if defined(NOISE) && !defined(PIXEL_COLOR)
    // How big a screen pixel is on the ground here, in blocks (before any branch, for the derivatives).
    vec3 noiseDx = abs(dFdx(noisePos));
    vec3 noiseDy = abs(dFdy(noisePos));
    float footprint = max(max(max(noiseDx.x, noiseDx.y), max(noiseDx.z, noiseDy.x)), max(noiseDy.y, noiseDy.z));
#endif
#ifdef STEPPED
    // Only on the stepped columns: their faces are flat and straight, so the slope that the derivatives give is the real one, wall or top.
    // The smooth ground has its slope in its vertex colors already, taken from the (smoothed) heights of the samples around each vertex
    // and blended between vertices, so it is lit without a seam across triangles, tiles or chunk borders; a normal taken from the
    // derivatives would change from one triangle to the next there, and its light would be put on top of the shade that is in the colors.
    // The slope is taken from how the view-space position changes across the screen (before any branch, for the derivatives):
    // the normal of the surface in world space, turned to face the camera. The terrain has no normals of its own.
    vec3 dpdx = dFdx(viewPos);
    vec3 dpdy = dFdy(viewPos);
    vec3 faceNormal = cross(dpdx, dpdy);
    float faceLength = length(faceNormal);
    vec3 worldNormal = vec3(0.0, 1.0, 0.0);
    if (faceLength > 1.0e-12) {
        vec3 viewNormal = faceNormal / faceLength;
        if (dot(viewNormal, viewPos) > 0.0) {
            viewNormal = -viewNormal;
        }
        // ModelViewMat is the rotation of the view: its transpose turns a view-space direction back into the world.
        worldNormal = normalize(viewNormal * mat3(ModelViewMat));
    }
#endif
#ifdef SPRITES
    // ColorModulator.x: the distance of vanilla chunks around the camera for trees, inside it they are the real ones; 0 elsewhere.
    if (ColorModulator.x > 0.5 && dot(relativeXZ, relativeXZ) < ColorModulator.x * ColorModulator.x) {
        discard;
    }
    if (spriteInfo.z > -0.5) {
        int pixel = spritePixel(int(spriteInfo.z + 0.5), spriteInfo.xy);
        if (pixel == 0) {
            discard;
        }
        if (pixel == 2) {
            smoothColor = vec4(vec3(75.0, 58.0, 40.0) / 255.0 * spriteLight, 1.0);
        }
    }
#endif
#ifdef PIXEL_COLOR
    // ColorModulator: y the size of a pixel in blocks, z the noise. The smooth color is read at the center of
    // the pixel the fragment is in, by continuing the blend along its slope (derivatives are taken outside of any branch).
    float cell = max(ColorModulator.y, 0.001);
    vec2 cellIndex = floor(localXZ / cell);
    vec2 d = (cellIndex + 0.5) * cell - localXZ;
    vec2 dpx = dFdx(localXZ);
    vec2 dpy = dFdy(localXZ);
    vec3 dcx = dFdx(vertexColor.rgb);
    vec3 dcy = dFdy(vertexColor.rgb);
    float det = dpx.x * dpy.y - dpx.y * dpy.x;
    if (abs(det) > 0.0001 * length(dpx) * length(dpy)) {
        float s = (d.x * dpy.y - d.y * dpy.x) / det;
        float t = (dpx.x * d.y - dpx.y * d.x) / det;
        smoothColor = vec4(clamp(vertexColor.rgb + dcx * s + dcy * t, 0.0, 1.0), vertexColor.a);
    }
#endif
    vec4 color = smoothColor;
#ifdef STEPPED
    // The walls are darker than the tops already, by their side, in their vertex colors: that is taken out again, and the light of
    // the surface they really face is put on, so that a cliff is lit on the side of the light and dark on the other, whichever way it runs.
    float wall = 1.0 - smoothstep(0.3, 0.6, abs(worldNormal.y));
    float baked = mix(1.0, abs(worldNormal.x) > abs(worldNormal.z) ? BAKED_EAST_WEST : BAKED_NORTH_SOUTH, wall);
    color.rgb = clamp(color.rgb / baked * surfaceLight(worldNormal), 0.0, 1.0);
#endif
#ifdef PIXEL_COLOR
    color.rgb *= 1.0 + (cellHash(cellIndex) * 2.0 - 1.0) * ColorModulator.z;
#elif defined(NOISE)
    // The stepped columns and the ground that is drawn smooth (its cells are smaller than a pixel): the noise goes in cells as big as the
    // tile's, or a pixel when that is bigger (a power of two, so that the grain changes only by whole steps, with the distance), fixed to the world.
    // ColorModulator.y: the size of a cell in blocks, z: the strength (0 when the pixel filter is off).
    float grain = max(max(ColorModulator.y, 0.001), exp2(ceil(log2(max(footprint, 1.0e-4)))));
    vec3 grainIndex = floor(noisePos / grain);
#ifndef STEPPED
    grainIndex.y = 0.0;
#endif
    color.rgb *= 1.0 + (cellHash(vec2(grainIndex.x + grainIndex.y * 57.0, grainIndex.z)) * 2.0 - 1.0) * ColorModulator.z;
#endif
#ifndef SPRITES
    // Inside the square of vanilla's chunks (the first column of the texture matrix, see LodRenderer: x0, z0, x1, z1 from the camera) vanilla
    // draws: nothing of the terrain is left there, so the two cannot fight over the depth. Last, after every derivative above.
    vec4 own = TextureMat[0];
    if (own.z > own.x && groundPos.x >= own.x && groundPos.x < own.z && groundPos.y >= own.y && groundPos.y < own.w) {
        discard;
    }
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, renderDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
