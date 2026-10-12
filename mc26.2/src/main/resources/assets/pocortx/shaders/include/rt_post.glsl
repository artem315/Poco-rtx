#ifndef POCORTX_RT_POST_GLSL
#define POCORTX_RT_POST_GLSL

// Shared by the Minecraft passes that denoise and composite the hardware ray tracing output.

layout(std140) uniform RtxFrame {
    mat4 RtxInvProj;
    mat4 RtxViewRot;    // camera-relative world -> view rotation
    vec4 RtxScreen;     // xy: full size, zw: trace size
    vec4 RtxLightColor; // rgb: sun/moon colour, w: light strength
    vec4 RtxLightDir;   // xyz: world direction to the sun/moon, w: share of sky light that is direct light
    vec4 RtxStrength;   // x: AO, y: shadows, z: volumetric light, w: reflections on
    vec4 RtxParams;     // x: debug view, y: RT distance, z: GI strength, w: RT lighting on
    vec4 RtxDepth;      // x: 1 when device depth is NDC z (zero-to-one clip range), y: sun/moon light scale
    vec4 RtxLmSky;      // vanilla lightmap: rgb sky light colour * sky factor, w: block factor
    vec4 RtxLmBlock;    // rgb: block light tint, w: brightness option
    vec4 RtxLmAmbient;  // rgb: ambient colour, w: darkness
    vec4 RtxFaceShade;  // vanilla face shading: down, up, north/south, east/west
};

uniform sampler2D OpaqueDepthSampler;

const int RTX_MAT_ENTITY = 7;
const int RTX_MAT_NONE = 255;

float rtxNdcZ(float d) {
    return RtxDepth.x > 0.5 ? d : d * 2.0 - 1.0;
}

vec3 rtxViewPos(vec2 uv, float depth) {
    vec4 v = RtxInvProj * vec4(uv * 2.0 - 1.0, rtxNdcZ(depth), 1.0);
    return v.xyz / v.w;
}

// Trace pixels stand for the full resolution depth texel under their centre.
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

float rtxDepthWeight(float a, float b) {
    return 1.0 / (1.0 + 60.0 * abs(a - b) / max(b, 0.05));
}

// ---------------------------------------------------------------- lighting model
// Same model as rt_common.glsl in the ray tracing kernels; keep them in sync.

float rtxLightCurve(float l) {
    return l / (4.0 - 3.0 * l);
}

// Vanilla lightmap (core/lightmap.fsh) before clamping and the brightness curve.
vec3 rtxVanillaLight(vec2 lm) {
    vec3 c = RtxLmAmbient.rgb + RtxLmSky.rgb * rtxLightCurve(lm.y);
    float pm = (2.0 * lm.x - 1.0) * (2.0 * lm.x - 1.0);
    c += mix(RtxLmBlock.rgb, vec3(1.0), 0.9 * pm) * (rtxLightCurve(lm.x) * RtxLmSky.w);
    return c - RtxLmAmbient.w;
}

float rtxFaceShade(vec3 n) {
    vec3 a = abs(n);
    if (a.y >= a.x && a.y >= a.z) {
        return n.y > 0.0 ? RtxFaceShade.y : RtxFaceShade.x;
    }
    return a.z >= a.x ? RtxFaceShade.z : RtxFaceShade.w;
}

vec3 rtxSunTint() {
    return RtxLightColor.rgb / max(max(RtxLightColor.r, RtxLightColor.g), max(RtxLightColor.b, 1e-3));
}

// Ray traced light arriving at a surface, in vanilla lightmap units.
vec3 rtxLight(vec3 n, vec2 lm, float vis, float ao) {
    float d = RtxLightDir.w;
    float hemi = 0.6 + 0.4 * n.y;
    vec3 sky = RtxLmSky.rgb * (rtxLightCurve(lm.y) * mix(1.0, 0.42, d) * hemi * ao);
    float ndl = max(dot(n, RtxLightDir.xyz), 0.0);
    // Sky light level gates direct light where rays cannot see far enough (caves) and dims it
    // a little under water and indoors.
    float gate = smoothstep(0.03, 0.35, lm.y) * mix(0.55, 1.0, rtxLightCurve(lm.y));
    vec3 sun = RtxLmSky.rgb * rtxSunTint() * (0.72 * d * RtxDepth.y * ndl * vis * gate);
    float pm = (2.0 * lm.x - 1.0) * (2.0 * lm.x - 1.0);
    vec3 block = mix(RtxLmBlock.rgb, vec3(1.0), 0.9 * pm) * (rtxLightCurve(lm.x) * RtxLmSky.w * mix(1.0, ao, 0.6));
    return RtxLmAmbient.rgb + sky + sun + block - RtxLmAmbient.w;
}

#endif
