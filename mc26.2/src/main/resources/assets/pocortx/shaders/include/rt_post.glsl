#ifndef POCORTX_RT_POST_GLSL
#define POCORTX_RT_POST_GLSL

// Shared by the Minecraft passes that denoise and composite the hardware ray tracing output.

layout(std140) uniform RtxFrame {
    mat4 RtxInvProj;
    vec4 RtxScreen;     // xy: full size, zw: trace size
    vec4 RtxLightColor; // rgb: sun/moon colour, w: light strength
    vec4 RtxStrength;   // x: AO, y: shadows (scaled by light), z: volumetric light, w: reflections on
    vec4 RtxParams;     // x: debug view, y: RT distance, z: submerged
    vec4 RtxDepth;      // x: 1 when device depth is NDC z (zero-to-one clip range)
    vec4 RtxPad;
};

uniform sampler2D OpaqueDepthSampler;

float rtxNdcZ(float d) {
    return RtxDepth.x > 0.5 ? d : d * 2.0 - 1.0;
}

vec3 rtxViewPos(vec2 uv, float depth) {
    vec4 v = RtxInvProj * vec4(uv * 2.0 - 1.0, rtxNdcZ(depth), 1.0);
    return v.xyz / v.w;
}

// Trace pixels sit between full resolution depth texels; snap to a texel centre.
vec2 rtxSnapUV(vec2 uv) {
    return (floor(uv * RtxScreen.xy) + 0.5) / RtxScreen.xy;
}

float rtxLinear(vec2 uv, float depth) {
    return depth <= 0.0000001 ? 100000.0 : -rtxViewPos(uv, depth).z;
}

float rtxOpaqueLinear(vec2 uv) {
    uv = rtxSnapUV(uv);
    return rtxLinear(uv, texture(OpaqueDepthSampler, uv).r);
}

#endif
