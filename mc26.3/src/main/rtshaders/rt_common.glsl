// PocoRTX hardware ray tracing: shared declarations for the compute passes.
// Positions are world space relative to the camera; depth is reversed-Z in [0, 1] (Vulkan).

layout(set = 0, binding = 0) uniform accelerationStructureEXT uScene;

layout(set = 0, binding = 1, std140) uniform Frame {
    mat4 invProj;
    mat4 proj;
    mat4 invViewRot;
    mat4 viewRot;
    mat4 prevReproj;    // current camera-relative world -> previous clip space
    vec4 screen;        // xy: full size, zw: trace size
    vec4 light;         // xyz: world direction to the sun/moon, w: direct light strength
    vec4 lightColor;    // rgb: light colour, w: daylight (sky brightness)
    vec4 skyColor;      // rgb: sky colour
    vec4 fogColor;      // rgb: horizon/fog colour, w: fog end
    vec4 camera;        // xyz: camera position (wrapped), w: time in seconds
    vec4 counts;        // x: AO rays, y: GI rays, z: volumetric steps, w: frame index
    vec4 toggles;       // x: AO, y: shadows, z: GI, w: reflections
    vec4 toggles2;      // x: specular, y: volumetric light, z: puddles, w: water waves
    vec4 strength;      // x: AO, y: shadows, z: GI, w: reflections
    vec4 params;        // x: RT distance, y: sun angular radius, z: wetness, w: alpha tested shadows
    vec4 params2;       // x: temporal blend, y: debug view, z: emissive strength, w: history valid
    vec4 params3;       // x: cloud height, y: volumetric strength, z: specular strength, w: submerged
    vec4 quality;       // x: shadow rays at GI hits, y: shadow rays at reflection hits, z: diffuse atlas LOD
} F;

layout(set = 0, binding = 2, std430) readonly buffer Instances {
    uvec4 entries[];    // 2 per instance: (addr0, addr1), (flags, 0, 0, 0)
};

layout(set = 0, binding = 3) uniform sampler2D uOpaqueDepth;
layout(set = 0, binding = 4) uniform sampler2D uFinalDepth;
layout(set = 0, binding = 5) uniform sampler2D uAtlas;
layout(set = 0, binding = 6) uniform sampler2D uMaterials;
layout(set = 0, binding = 7) uniform sampler2D uHistory;     // this kernel's previous accumulated output
layout(set = 0, binding = 13) uniform sampler2D uHistLight;  // previous light output: alpha = view depth

layout(buffer_reference, std430, buffer_reference_align = 4) readonly buffer Verts {
    uint w[];
};

const uint MASK_OPAQUE = 1u;
const uint MASK_TRANSLUCENT = 2u;
const uint MASK_ENTITY = 4u;
const uint FLAG_TRANSLUCENT = 4u;
const uint FLAG_ENTITY = 8u;     // entity vertex layout (36 bytes), textures not in the block atlas
const float PI = 3.14159265;

// Material ids written by RtMaterials (alpha channel of the material texture).
const int MAT_DEFAULT = 0;
const int MAT_WATER = 1;
const int MAT_GLASS = 2;
const int MAT_ICE = 3;
const int MAT_METAL = 4;
const int MAT_GEM = 5;
const int MAT_GLOW_PARTIAL = 6;

// ---------------------------------------------------------------- random

uint pcg(uint v) {
    uint state = v * 747796405u + 2891336453u;
    uint word = ((state >> ((state >> 28u) + 4u)) ^ state) * 277803737u;
    return (word >> 22u) ^ word;
}

struct Rng { uint s; };

float rnd(inout Rng r) {
    r.s = pcg(r.s);
    return float(r.s) * (1.0 / 4294967296.0);
}

vec3 cosineDir(vec3 n, inout Rng r) {
    float u = rnd(r), v = rnd(r);
    float a = 2.0 * PI * u;
    float s = sqrt(v);
    vec3 t = normalize(abs(n.y) < 0.99 ? cross(n, vec3(0.0, 1.0, 0.0)) : cross(n, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(n, t);
    return normalize(t * (cos(a) * s) + b * (sin(a) * s) + n * sqrt(max(0.0, 1.0 - v)));
}

vec3 coneDir(vec3 dir, float radius, inout Rng r) {
    float u = rnd(r), v = rnd(r);
    float a = 2.0 * PI * u;
    float s = radius * sqrt(v);
    vec3 t = normalize(abs(dir.y) < 0.99 ? cross(dir, vec3(0.0, 1.0, 0.0)) : cross(dir, vec3(1.0, 0.0, 0.0)));
    vec3 b = cross(dir, t);
    return normalize(dir + (t * cos(a) + b * sin(a)) * s);
}

// ---------------------------------------------------------------- camera

vec3 worldPos(vec2 uv, float depth) {
    vec4 v = F.invProj * vec4(uv * 2.0 - 1.0, depth, 1.0);
    v /= v.w;
    return (F.invViewRot * vec4(v.xyz, 0.0)).xyz;
}

float viewDepth(vec3 p) {
    return -(F.viewRot * vec4(p, 0.0)).z;
}

// ---------------------------------------------------------------- geometry

struct Hit {
    bool hit;
    float t;
    vec3 n;         // geometric normal facing the ray
    vec2 uv;        // block atlas
    vec4 color;     // vertex colour: biome tint and baked shading
    vec2 lm;        // block light, sky light in [0, 1]
    bool translucent;
    bool entity;
};

uvec2 geometryAddress(uint ci, uint geom) {
    uvec4 e = entries[ci * 2u];
    return geom == 0u ? e.xy : e.zw;
}

void triIndices(uint prim, out uint i0, out uint i1, out uint i2) {
    uint base = (prim >> 1u) * 4u;
    if ((prim & 1u) == 0u) {
        i0 = base; i1 = base + 1u; i2 = base + 2u;
    } else {
        i0 = base + 2u; i1 = base + 3u; i2 = base;
    }
}

// Block vertices are 7 words (pos, colour, uv0, uv2); entity vertices 9 (pos, colour, uv0, uv1, uv2, normal).
vec3 vPos(Verts v, uint i, uint stride) {
    uint b = i * stride;
    return vec3(uintBitsToFloat(v.w[b]), uintBitsToFloat(v.w[b + 1u]), uintBitsToFloat(v.w[b + 2u]));
}

vec4 vColor(Verts v, uint i, uint stride) {
    return unpackUnorm4x8(v.w[i * stride + 3u]);
}

vec2 vUv(Verts v, uint i, uint stride) {
    uint b = i * stride + 4u;
    return vec2(uintBitsToFloat(v.w[b]), uintBitsToFloat(v.w[b + 1u]));
}

vec2 vLight(Verts v, uint i, uint stride) {
    uint p = v.w[i * stride + (stride == 9u ? 7u : 6u)];
    return vec2(float(p & 0xFFFFu), float(p >> 16u)) / 240.0;
}

vec2 hitUv(uint ci, uint geom, uint prim, vec2 bary) {
    Verts v = Verts(geometryAddress(ci, geom));
    uint i0, i1, i2;
    triIndices(prim, i0, i1, i2);
    vec3 w = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    return vUv(v, i0, 7u) * w.x + vUv(v, i1, 7u) * w.y + vUv(v, i2, 7u) * w.z;
}

// Alpha test for cutout (leaves, grass) and translucent (water, glass) candidates.
bool candidateOpaque(rayQueryEXT rq) {
    uint ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, false);
    uint geom = rayQueryGetIntersectionGeometryIndexEXT(rq, false);
    uint prim = rayQueryGetIntersectionPrimitiveIndexEXT(rq, false);
    vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, false);
    bool translucent = (entries[ci * 2u + 1u].x & FLAG_TRANSLUCENT) != 0u;
    float a = textureLod(uAtlas, hitUv(ci, geom, prim, bary), 0.0).a;
    return a > (translucent ? 0.02 : 0.5);
}

bool traceAny(vec3 o, vec3 d, float tMax, uint mask, bool alphaTest) {
    rayQueryEXT rq;
    uint flags = gl_RayFlagsTerminateOnFirstHitEXT | (alphaTest ? 0u : gl_RayFlagsOpaqueEXT);
    rayQueryInitializeEXT(rq, uScene, flags, mask, o, 0.0, d, tMax);
    while (rayQueryProceedEXT(rq)) {
        if (rayQueryGetIntersectionTypeEXT(rq, false) == gl_RayQueryCandidateIntersectionTriangleEXT && candidateOpaque(rq)) {
            rayQueryConfirmIntersectionEXT(rq);
        }
    }
    return rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionNoneEXT;
}

Hit traceClosest(vec3 o, vec3 d, float tMax, uint mask, bool alphaTest) {
    rayQueryEXT rq;
    uint flags = alphaTest ? 0u : gl_RayFlagsOpaqueEXT;
    rayQueryInitializeEXT(rq, uScene, flags, mask, o, 0.0, d, tMax);
    while (rayQueryProceedEXT(rq)) {
        if (rayQueryGetIntersectionTypeEXT(rq, false) == gl_RayQueryCandidateIntersectionTriangleEXT && candidateOpaque(rq)) {
            rayQueryConfirmIntersectionEXT(rq);
        }
    }
    Hit h;
    h.hit = rayQueryGetIntersectionTypeEXT(rq, true) != gl_RayQueryCommittedIntersectionNoneEXT;
    if (!h.hit) {
        h.t = tMax;
        return h;
    }
    uint ci = rayQueryGetIntersectionInstanceCustomIndexEXT(rq, true);
    uint geom = rayQueryGetIntersectionGeometryIndexEXT(rq, true);
    uint prim = rayQueryGetIntersectionPrimitiveIndexEXT(rq, true);
    vec2 bary = rayQueryGetIntersectionBarycentricsEXT(rq, true);
    h.t = rayQueryGetIntersectionTEXT(rq, true);
    uint instanceFlags = entries[ci * 2u + 1u].x;
    h.translucent = (instanceFlags & FLAG_TRANSLUCENT) != 0u;
    h.entity = (instanceFlags & FLAG_ENTITY) != 0u;
    uint stride = h.entity ? 9u : 7u;
    Verts v = Verts(geometryAddress(ci, geom));
    uint i0, i1, i2;
    triIndices(prim, i0, i1, i2);
    vec3 w = vec3(1.0 - bary.x - bary.y, bary.x, bary.y);
    vec3 p0 = vPos(v, i0, stride), p1 = vPos(v, i1, stride), p2 = vPos(v, i2, stride);
    vec3 n = cross(p1 - p0, p2 - p0);
    n = dot(n, n) > 1e-12 ? normalize(n) : -d;
    h.n = dot(n, d) > 0.0 ? -n : n;
    h.uv = vUv(v, i0, stride) * w.x + vUv(v, i1, stride) * w.y + vUv(v, i2, stride) * w.z;
    h.color = vColor(v, i0, stride) * w.x + vColor(v, i1, stride) * w.y + vColor(v, i2, stride) * w.z;
    h.lm = vLight(v, i0, stride) * w.x + vLight(v, i1, stride) * w.y + vLight(v, i2, stride) * w.z;
    return h;
}

// ---------------------------------------------------------------- materials and lighting

struct Material {
    float roughness;
    float f0;
    bool metal;
    float emissive;
    int id;
};

Material material(vec2 uv) {
    vec4 m = textureLod(uMaterials, uv, 0.0);
    Material mat;
    mat.roughness = m.r;
    mat.metal = m.g > 0.9;
    mat.f0 = mat.metal ? 1.0 : m.g;
    mat.emissive = m.b;
    mat.id = int(m.a * 255.0 + 0.5);
    return mat;
}

float luma(vec3 c) {
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// Vanilla-like lightmap brightness, so ray traced hits match the rasterised world.
float lightCurve(float l) {
    return l / (4.0 - 3.0 * l);
}

vec3 skyRadiance(vec3 d) {
    float h = d.y;
    vec3 sky = mix(F.fogColor.rgb, F.skyColor.rgb, smoothstep(-0.05, 0.4, h));
    sky = mix(sky, F.fogColor.rgb * 0.45, smoothstep(0.0, -0.35, h));
    float sd = max(dot(d, F.light.xyz), 0.0);
    sky += F.lightColor.rgb * F.light.w * (pow(sd, 900.0) * 12.0 + pow(sd, 10.0) * 0.18);
    return sky;
}

float fresnel(float f0, float cosTheta) {
    return f0 + (1.0 - f0) * pow(1.0 - clamp(cosTheta, 0.0, 1.0), 5.0);
}

float ggxSpecular(vec3 n, vec3 v, vec3 l, float roughness) {
    vec3 h = normalize(v + l);
    float a = max(roughness * roughness, 0.0025);
    float a2 = a * a;
    float ndh = max(dot(n, h), 0.0);
    float ndl = max(dot(n, l), 0.0);
    float ndv = max(dot(n, v), 1e-3);
    float d = a2 / (PI * pow(ndh * ndh * (a2 - 1.0) + 1.0, 2.0));
    float k = a * 0.5;
    float g = (ndl / (ndl * (1.0 - k) + k)) * (ndv / (ndv * (1.0 - k) + k));
    return d * g / (4.0 * ndv * max(ndl, 1e-3) + 1e-4) * ndl;
}

vec3 waterNormal(vec3 worldPos, vec3 n) {
    if (F.toggles2.w < 0.5 || n.y < 0.5) {
        return n;
    }
    vec2 p = worldPos.xz;
    float t = F.camera.w;
    vec2 g = vec2(0.0);
    g += vec2(0.8, 0.6) * cos(dot(p, vec2(0.8, 0.6)) * 1.3 + t * 1.6) * 0.035;
    g += vec2(-0.5, 0.86) * cos(dot(p, vec2(-0.5, 0.86)) * 2.1 + t * 2.1) * 0.022;
    g += vec2(0.17, -0.98) * cos(dot(p, vec2(0.17, -0.98)) * 3.7 + t * 2.9) * 0.012;
    return normalize(vec3(-g.x, 1.0, -g.y));
}

float hash2(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash2(i), hash2(i + vec2(1, 0)), u.x), mix(hash2(i + vec2(0, 1)), hash2(i + vec2(1, 1)), u.x), u.y);
}
