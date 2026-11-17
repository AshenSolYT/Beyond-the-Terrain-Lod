#version 330

#extension GL_ARB_separate_shader_objects : require

#include <minecraft:fog.glsl>
#include <minecraft:dynamictransforms.glsl>

layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
layout(location = 2) in vec4 vertexColor;
#ifdef PIXEL_COLOR
layout(location = 3) in vec2 localXZ;
#endif

layout(location = 0) out vec4 fragColor;

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
    // The last value of the fog buffer tells the shape of the fog: round or cylindrical, see LodFog.
    float renderDistance = FogCloudsEnd > 0.5 ? sphericalVertexDistance : cylindricalVertexDistance;
    vec4 smoothColor = vertexColor;
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
