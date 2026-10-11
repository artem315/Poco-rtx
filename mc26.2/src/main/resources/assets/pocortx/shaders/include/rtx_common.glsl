#ifndef POCORTX_COMMON_GLSL
#define POCORTX_COMMON_GLSL

// PocoRTX shared helpers for the renderpearl pipeline (26.x).
// Depth is reversed-Z: near = 1, sky/far = 0. Whether device depth equals NDC z
// depends on the backend and is passed in RtxDepth.x.

layout(std140) uniform RtxFrame {
    mat4 RtxProj;
    mat4 RtxInvProj;
    mat4 RtxView;
    mat4 RtxInvView;
    vec4 RtxScreen;     // xy: full size, zw: trace size
    vec4 RtxLight;      // xyz: view-space direction to the sun/moon, w: light strength
    vec4 RtxSunColor;   // rgb: light colour, w: glint strength
    vec4 RtxSkyColor;   // rgb: sky colour, w: sky exposure at the camera
    vec4 RtxFogColor;   // rgb: fog colour, w: fade distance
    vec4 RtxCamera;     // xyz: camera position (x/z wrapped), w: time in seconds
    vec4 RtxCounts;     // x: AO samples, y: shadow steps, z: light shaft steps, w: reflection steps
    vec4 RtxToggles;    // x: AO, y: shadows, z: light shafts, w: reflections
    vec4 RtxStrength;   // x: AO, y: shadows, z: reflections, w: light shafts
    vec4 RtxParams;     // x: AO radius, y: shadow ray length, z: reflection ray length, w: wetness
    vec4 RtxParams2;    // x: cloud height, y: water waves, z: debug view, w: puddles
    vec4 RtxDepth;      // x: 1 when device depth is NDC z (zero-to-one clip range)
};

uniform sampler2D OpaqueDepthSampler;
uniform sampler2D FinalDepthSampler;

bool rtxIsSky(float d) {
    return d <= 0.0000001;
}

float rtxNdcZ(float d) {
    return RtxDepth.x > 0.5 ? d : d * 2.0 - 1.0;
}

float rtxDeviceDepth(float ndcZ) {
    return RtxDepth.x > 0.5 ? ndcZ : ndcZ * 0.5 + 0.5;
}

float rtxOpaqueDepth(vec2 uv) {
    return texture(OpaqueDepthSampler, uv).r;
}

float rtxFinalDepth(vec2 uv) {
    return texture(FinalDepthSampler, uv).r;
}

vec3 rtxViewPos(vec2 uv, float depth) {
    vec4 v = RtxInvProj * vec4(uv * 2.0 - 1.0, rtxNdcZ(depth), 1.0);
    return v.xyz / v.w;
}

// Returns screen uv in xy and device depth in z.
vec3 rtxProject(vec3 viewPos) {
    vec4 c = RtxProj * vec4(viewPos, 1.0);
    vec3 ndc = c.xyz / c.w;
    return vec3(ndc.xy * 0.5 + 0.5, rtxDeviceDepth(ndc.z));
}

float rtxLinearDist(vec2 uv, float depth) {
    return rtxIsSky(depth) ? 100000.0 : -rtxViewPos(uv, depth).z;
}

// Reduced-resolution passes land exactly between full-resolution depth texels;
// snap to a texel centre so neighbour fetches never alias the centre fetch.
vec2 rtxSnapUV(vec2 uv) {
    return (floor(uv * RtxScreen.xy) + 0.5) / RtxScreen.xy;
}

bool rtxOnScreen(vec2 uv) {
    return uv.x >= 0.0 && uv.y >= 0.0 && uv.x <= 1.0 && uv.y <= 1.0;
}

float rtxNoise(vec2 fragCoord) {
    return fract(52.9829189 * fract(dot(fragCoord, vec2(0.06711056, 0.00583715))));
}

vec3 rtxNormal(vec2 uv, vec3 p, bool opaque) {
    vec2 px = 1.0 / RtxScreen.xy;
    vec2 uvR = uv + vec2(px.x, 0.0);
    vec2 uvL = uv - vec2(px.x, 0.0);
    vec2 uvU = uv + vec2(0.0, px.y);
    vec2 uvD = uv - vec2(0.0, px.y);
    vec3 pR = rtxViewPos(uvR, opaque ? rtxOpaqueDepth(uvR) : rtxFinalDepth(uvR));
    vec3 pL = rtxViewPos(uvL, opaque ? rtxOpaqueDepth(uvL) : rtxFinalDepth(uvL));
    vec3 pU = rtxViewPos(uvU, opaque ? rtxOpaqueDepth(uvU) : rtxFinalDepth(uvU));
    vec3 pD = rtxViewPos(uvD, opaque ? rtxOpaqueDepth(uvD) : rtxFinalDepth(uvD));
    vec3 dx = abs(pR.z - p.z) < abs(p.z - pL.z) ? pR - p : p - pL;
    vec3 dy = abs(pU.z - p.z) < abs(p.z - pD.z) ? pU - p : p - pD;
    vec3 n = cross(dx, dy);
    float len = length(n);
    // Facing the camera regardless of the screen-space y convention of the backend.
    n = len > 1e-8 ? n / len : vec3(0.0, 0.0, 1.0);
    return dot(n, -p) < 0.0 ? -n : n;
}

vec3 rtxToWorldDir(vec3 v) {
    return mat3(RtxInvView) * v;
}

vec3 rtxToViewDir(vec3 w) {
    return mat3(RtxView) * w;
}

vec3 rtxSnapWorldNormal(vec3 n) {
    vec3 a = abs(n);
    float m = max(a.x, max(a.y, a.z));
    if (m < 0.92) {
        return n;
    }
    if (a.y >= m) {
        return vec3(0.0, n.y > 0.0 ? 1.0 : -1.0, 0.0);
    }
    if (a.x >= m) {
        return vec3(n.x > 0.0 ? 1.0 : -1.0, 0.0, 0.0);
    }
    return vec3(0.0, 0.0, n.z > 0.0 ? 1.0 : -1.0);
}

#endif
