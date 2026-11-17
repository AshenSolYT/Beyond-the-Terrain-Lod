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
layout(location = 1) out float cylindricalVertexDistance;
layout(location = 2) out vec4 vertexColor;
#ifdef PIXEL_COLOR
layout(location = 3) out vec2 localXZ;
#endif

void main() {
    vec3 pos = Position + ModelOffset;
    gl_Position = ProjMat * (ModelViewMat * vec4(pos, 1.0));
    sphericalVertexDistance = fog_spherical_distance(pos);
    cylindricalVertexDistance = fog_cylindrical_distance(pos);
    vec4 light = sample_lightmap(Sampler2, ivec2(0, 240));
    vertexColor = vec4(Color.rgb * light.rgb, Color.a);
#ifdef PIXEL_COLOR
    localXZ = Position.xz;
#endif
}
