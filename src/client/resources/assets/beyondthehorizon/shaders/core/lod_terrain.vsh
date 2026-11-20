#version 330

#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>

uniform sampler2D Sampler2;

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;

layout(location = 0) out float sphericalVertexDistance;
layout(location = 2) out vec4 vertexColor;
#ifdef PIXEL_COLOR
layout(location = 3) out vec2 localXZ;
#endif
#ifdef SPRITES
// x, y: where on the sprite this corner is; z: which sprite (0 small, 1 big, 2 second big), -1 for a vertex that is no sprite.
layout(location = 3) out vec3 spriteInfo;
layout(location = 4) out vec3 spriteLight;
// Where the vertex is seen from the camera, for the trees that are not drawn inside the distance of vanilla chunks.
layout(location = 5) out vec2 relativeXZ;
#endif

void main() {
    vec3 pos = Position + ModelOffset;
    gl_Position = ProjMat * (ModelViewMat * vec4(pos, 1.0));
    // Radial fog: the full 3D distance from the camera (pos is camera-relative), so that the fog forms a sphere, not a cylinder.
    sphericalVertexDistance = length(pos);
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
