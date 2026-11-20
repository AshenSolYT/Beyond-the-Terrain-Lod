#version 330

#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 2) in vec4 vertexColor;
#ifdef PIXEL_COLOR
layout(location = 3) in vec2 localXZ;
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

#ifdef PIXEL_COLOR
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
    // The fog is always radial: the render-distance fog is measured like the environmental one, by the 3D distance.
    float renderDistance = sphericalVertexDistance;
    vec4 smoothColor = vertexColor;
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
#ifdef PIXEL_COLOR
    color.rgb *= 1.0 + (cellHash(cellIndex) * 2.0 - 1.0) * ColorModulator.z;
#endif
    fragColor = apply_fog(color, sphericalVertexDistance, renderDistance, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
}
