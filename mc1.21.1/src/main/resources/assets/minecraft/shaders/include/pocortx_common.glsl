// PocoRTX shared helpers.
// Written as desktop GLSL 1.50 (what vanilla uses) with explicit precision
// statements, so GLES translators (LTW, MobileGlues, GL4ES, Zink) keep highp
// for depth math on Mali/Immortalis GPUs.
precision highp float;
precision highp int;
precision highp sampler2D;

#define RTX_SKY_DEPTH 0.999999

uniform mat4 RtxProj;
uniform mat4 RtxInvProj;
uniform mat4 RtxView;
uniform mat4 RtxInvView;
uniform vec2 RtxScreen;

// Depth of the scene before translucent terrain (water, stained glass, ice).
uniform sampler2D OpaqueSampler;
// Depth of the finished scene, translucent surfaces included.
uniform sampler2D FinalSampler;

float rtxUnpackDepth(vec4 c) {
    vec3 b = floor(c.rgb * 255.0 + 0.5);
    return (b.r * 65536.0 + b.g * 256.0 + b.b) / 16777215.0;
}

float rtxOpaqueDepth(vec2 uv) {
    return rtxUnpackDepth(texture(OpaqueSampler, uv));
}

float rtxFinalDepth(vec2 uv) {
    return rtxUnpackDepth(texture(FinalSampler, uv));
}

vec3 rtxViewPos(vec2 uv, float depth) {
    vec4 v = RtxInvProj * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return v.xyz / v.w;
}

// Returns screen uv in xy and window depth in z.
vec3 rtxProject(vec3 viewPos) {
    vec4 c = RtxProj * vec4(viewPos, 1.0);
    return (c.xyz / c.w) * 0.5 + 0.5;
}

// Reduced-resolution passes land exactly between full-resolution depth texels;
// snap to a texel centre so neighbour fetches never alias the centre fetch.
vec2 rtxSnapUV(vec2 uv) {
    return (floor(uv * RtxScreen) + 0.5) / RtxScreen;
}

bool rtxOnScreen(vec2 uv) {
    return uv.x >= 0.0 && uv.y >= 0.0 && uv.x <= 1.0 && uv.y <= 1.0;
}

// Interleaved gradient noise: cheap, stable per pixel, hides banding of short ray marches.
float rtxNoise(vec2 fragCoord) {
    return fract(52.9829189 * fract(dot(fragCoord, vec2(0.06711056, 0.00583715))));
}

// Picks the neighbour with the smaller depth step on each axis, which keeps
// normals clean on block edges instead of smearing across silhouettes.
vec3 rtxNormal(vec2 uv, vec3 p, bool opaque) {
    vec2 px = 1.0 / RtxScreen;
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
    return len > 1e-8 ? n / len : vec3(0.0, 0.0, 1.0);
}

vec3 rtxToWorldDir(vec3 v) {
    return mat3(RtxInvView) * v;
}

vec3 rtxToViewDir(vec3 w) {
    return mat3(RtxView) * w;
}

// Minecraft is mostly axis aligned: snapping near-axis normals removes the
// stair-step noise that depth reconstruction produces on distant blocks.
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
