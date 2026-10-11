// Pieces shared by the three ray tracing kernels: the per-pixel setup, the G-buffer
// encoding and the temporal reprojection.

struct Surface {
    vec3 p;
    vec3 n;
    vec3 albedo;
    vec2 lm;
    Material mat;
    bool exact;     // the primary ray found the rasterised surface in the acceleration structure
};

struct Pixel {
    ivec2 pix;
    ivec2 fullPix;
    vec2 uv;
    float od;
    float fd;
    vec3 viewDir;
    bool sky;
};

bool setupPixel(out Pixel px) {
    px.pix = ivec2(gl_GlobalInvocationID.xy);
    if (any(greaterThanEqual(px.pix, ivec2(F.screen.zw)))) {
        return false;
    }
    px.uv = (vec2(px.pix) + 0.5) / F.screen.zw;
    px.fullPix = min(ivec2(px.uv * F.screen.xy), ivec2(F.screen.xy) - 1);
    px.od = texelFetch(uOpaqueDepth, px.fullPix, 0).r;
    px.fd = texelFetch(uFinalDepth, px.fullPix, 0).r;
    px.viewDir = normalize(worldPos(px.uv, 0.5));
    px.sky = px.od <= 1e-7;
    return true;
}

Rng makeRng(ivec2 pix, uint salt) {
    Rng rng;
    rng.s = pcg(uint(pix.x) * 1973u + uint(pix.y) * 9277u + uint(F.counts.w) * 26699u + salt * 104729u);
    return rng;
}

vec3 depthNormal(ivec2 pix, vec3 p) {
    ivec2 size = ivec2(F.screen.xy);
    ivec2 r = min(pix + ivec2(1, 0), size - 1), l = max(pix - ivec2(1, 0), ivec2(0));
    ivec2 u = min(pix + ivec2(0, 1), size - 1), d = max(pix - ivec2(0, 1), ivec2(0));
    vec3 pr = worldPos((vec2(r) + 0.5) / F.screen.xy, texelFetch(uOpaqueDepth, r, 0).r);
    vec3 pl = worldPos((vec2(l) + 0.5) / F.screen.xy, texelFetch(uOpaqueDepth, l, 0).r);
    vec3 pu = worldPos((vec2(u) + 0.5) / F.screen.xy, texelFetch(uOpaqueDepth, u, 0).r);
    vec3 pd = worldPos((vec2(d) + 0.5) / F.screen.xy, texelFetch(uOpaqueDepth, d, 0).r);
    vec3 dx = length(pr - p) < length(p - pl) ? pr - p : p - pl;
    vec3 dy = length(pu - p) < length(p - pd) ? pu - p : p - pd;
    vec3 n = normalize(cross(dx, dy));
    return dot(n, p) > 0.0 ? -n : n;
}

// G-buffer: normal + roughness (negative when not exact), albedo + F0, light + emission + id.
Surface readSurface(Pixel px, sampler2D gNormal, sampler2D gAlbedo, sampler2D gInfo) {
    Surface s;
    vec4 a = texelFetch(gNormal, px.pix, 0);
    vec4 b = texelFetch(gAlbedo, px.pix, 0);
    vec4 c = texelFetch(gInfo, px.pix, 0);
    s.p = worldPos(px.uv, px.od);
    s.n = normalize(a.xyz);
    s.exact = a.w >= 0.0;
    s.mat.roughness = abs(a.w);
    s.albedo = b.rgb;
    s.mat.metal = b.a > 0.9;
    s.mat.f0 = s.mat.metal ? 1.0 : b.a;
    s.lm = c.rg;
    s.mat.emissive = c.b;
    s.mat.id = int(c.a * 255.0 + 0.5);
    return s;
}

// Fraction of the history to keep, or 1.0 when this pixel has no valid history.
float temporalAlpha(Pixel px, out vec2 puv) {
    puv = vec2(-1.0);
    if (F.params2.w < 0.5 || F.params2.x >= 1.0 || px.sky) {
        return 1.0;
    }
    vec3 p = worldPos(px.uv, px.od);
    vec4 prev = F.prevReproj * vec4(p, 1.0);
    if (prev.w <= 1e-4) {
        return 1.0;
    }
    puv = prev.xy / prev.w * 0.5 + 0.5;
    if (any(lessThanEqual(puv, vec2(0.0))) || any(greaterThanEqual(puv, vec2(1.0)))) {
        return 1.0;
    }
    float histDepth = textureLod(uHistLight, puv, 0.0).a;
    return abs(histDepth - prev.w) < max(0.05, prev.w * 0.03) ? F.params2.x : 1.0;
}

float sunVisibility(vec3 p, vec3 n, inout Rng rng, bool alphaTest) {
    if (F.light.w <= 0.001 || dot(n, F.light.xyz) <= 0.0) {
        return 0.0;
    }
    vec3 l = coneDir(F.light.xyz, F.params.y, rng);
    return traceAny(p + n * 0.02, l, F.params.x * 1.5, MASK_OPAQUE | MASK_ENTITY, alphaTest) ? 0.0 : 1.0;
}
