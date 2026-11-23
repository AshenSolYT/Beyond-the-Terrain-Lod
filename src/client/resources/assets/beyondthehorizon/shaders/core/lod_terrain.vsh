#version 330

#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>

#ifdef PIXEL_COLOR
#define NOISE
#endif

uniform sampler2D Sampler2;

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out float sphericalVertexDistance;
// The distance on the ground plane only: vanilla's render-distance fog is measured with it (see lod_terrain.fsh, ColorModulator.w).
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
// Where the vertex is in view space, for the fragment shader to get the slope of the surface from.
layout(location = 6) out vec3 viewPos;
// Where the vertex is from the camera on the ground plane, to cut out what lies inside the square of vanilla's chunks.
layout(location = 7) out vec2 groundPos;
#ifdef PIXEL_COLOR
layout(location = 3) out vec2 localXZ;
#endif
#ifdef NOISE
// Where the vertex is in the world (x, y and z, each taken modulo 4096 so that a float holds it exactly enough): the grain of the noise is fixed to the world.
layout(location = 8) out vec3 noisePos;
#endif
#ifdef SPRITES
// x, y: where on the sprite this corner is; z: which sprite (0 small, 1 big, 2 second big), -1 for a vertex that is no sprite.
layout(location = 3) out vec3 spriteInfo;
layout(location = 4) out vec3 spriteLight;
// Where the vertex is seen from the camera, for the trees that are not drawn inside the distance of vanilla chunks.
layout(location = 5) out vec2 relativeXZ;
#endif

// The terrain is pushed this much further out along the line of sight for the depth only (the picture stays the same, as a point moved
// along its own ray lands on the same pixel). In the ring where vanilla's chunks and the terrain both are, vanilla's chunks then win
// without z-fighting, even where the coarser terrain is higher. Keep in step with PACK_DEPTH_SCALE in LodRenderer (the same bias under a pack).
#define LOD_DEPTH_SCALE 1.015

void main() {
    vec3 pos = Position + ModelOffset;
    vec4 viewPosition = ModelViewMat * vec4(pos, 1.0);
    gl_Position = ProjMat * vec4(viewPosition.xyz * LOD_DEPTH_SCALE, 1.0);
    viewPos = viewPosition.xyz;
    groundPos = pos.xz;
#ifdef NOISE
    // The second column of the texture matrix: the camera's position modulo 4096 (see LodRenderer), so that pos + it is the world position.
    noisePos = pos + TextureMat[1].xyz;
#endif
    // Radial fog: the full 3D distance from the camera (pos is camera-relative), so that the fog forms a sphere, not a cylinder.
    sphericalVertexDistance = length(pos);
    cylindricalVertexDistance = length(pos.xz);
    vec4 light = sample_lightmap(Sampler2, ivec2(0, 240));
    vertexColor = vec4(Color.rgb * light.rgb, Color.a);
#ifdef PIXEL_COLOR
    localXZ = Position.xz;
#endif
#ifdef SPRITES
    // The corners of a billboard tell their place on the sprite in the alpha: 128 + sprite * 4 + v * 2 + u. Everything else is opaque.
    int code = int(Color.a * 255.0 + 0.5);
    spriteInfo = vec3(0.0, 0.0, -1.0);
    spriteLight = light.rgb;
    relativeXZ = pos.xz;
    if (code >= 128 && code < 240) {
        code -= 128;
        spriteInfo = vec3(float(code & 1), float((code >> 1) & 1), float((code >> 2) & 3));
        vertexColor = vec4(Color.rgb * light.rgb, 1.0);
    }
#endif
}
