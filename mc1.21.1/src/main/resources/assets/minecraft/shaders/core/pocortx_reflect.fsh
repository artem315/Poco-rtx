#version 150

#moj_import <pocortx_common.glsl>

// Screen-space ray traced reflections for water, translucent blocks and
// rain puddles, rendered at reduced resolution.
// Output: rgb = reflected colour, a = reflection weight (fresnel * confidence).

uniform sampler2D SceneSampler;

uniform vec3 LightDir;      // view space
uniform vec3 SunColor;      // light colour * strength
uniform vec3 SkyColor;
uniform vec3 FogColor;
uniform vec3 CameraPos;     // world position, x/z wrapped to keep float precision
uniform vec4 ReflParams;    // x: march steps, y: max ray length, z: wetness, w: sky exposure
uniform vec4 ReflParams2;   // x: time (seconds), y: cloud height, z: fade distance, w: water waves
uniform vec4 ReflToggles;   // x: water strength, y: glass strength, z: puddle strength

in vec2 texCoord;

out vec4 fragColor;

vec2 waveTerm(vec2 p, vec2 dir, float freq, float amp, float speed, float t) {
    float phase = dot(p, dir) * freq + t * speed;
    return dir * (cos(phase) * freq * amp);
}

// Gradient of a small sum of directional waves.
vec2 waveSlope(vec2 xz, float t) {
    vec2 s = vec2(0.0);
    s += waveTerm(xz, vec2(0.80, 0.60), 1.6, 0.045, 1.3, t);
    s += waveTerm(xz, vec2(-0.45, 0.89), 2.3, 0.030, 1.7, t);
    s += waveTerm(xz, vec2(0.25, -0.97), 3.7, 0.018, 2.3, t);
    s += waveTerm(xz, vec2(-0.93, -0.36), 5.9, 0.010, 3.1, t);
    s += waveTerm(xz, vec2(0.60, -0.80), 9.1, 0.005, 4.3, t);
    return s;
}

float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash12(i);
    float b = hash12(i + vec2(1.0, 0.0));
    float c = hash12(i + vec2(0.0, 1.0));
    float d = hash12(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

vec3 skyReflection(vec3 rw) {
    vec3 c = mix(FogColor, SkyColor, smoothstep(-0.05, 0.4, rw.y));
    vec3 lw = rtxToWorldDir(LightDir);
    float sd = max(dot(rw, lw), 0.0);
    c += SunColor * (pow(sd, 600.0) * 6.0 + pow(sd, 40.0) * 0.12);
    return c;
}

// Marches the reflected ray in screen space (perspective-correct 1/z), then
// refines the first crossing with a short binary search.
float traceReflection(vec3 origin, vec3 dir, float noise, out vec2 hitUV) {
    hitUV = vec2(0.0);
    float maxDist = ReflParams.y;
    if (dir.z > 0.0) {
        maxDist = min(maxDist, (-0.1 - origin.z) / dir.z);
    }
    if (maxDist <= 0.05) {
        return 0.0;
    }
    vec3 endP = origin + dir * maxDist;
    vec3 s0 = rtxProject(origin);
    vec3 s1 = rtxProject(endP);
    float k0 = -1.0 / origin.z;
    float k1 = -1.0 / endP.z;

    vec2 d = s1.xy - s0.xy;
    float tMax = 1.0;
    if (d.x > 1e-6) tMax = min(tMax, (1.0 - s0.x) / d.x);
    if (d.x < -1e-6) tMax = min(tMax, -s0.x / d.x);
    if (d.y > 1e-6) tMax = min(tMax, (1.0 - s0.y) / d.y);
    if (d.y < -1e-6) tMax = min(tMax, -s0.y / d.y);
    if (tMax <= 0.0) {
        return 0.0;
    }

    int steps = int(ReflParams.x + 0.5);
    float prevT = 0.0;
    float prevZ = 1.0 / k0;
    for (int i = 0; i < 64; i++) {
        if (i >= steps) {
            break;
        }
        float f = (float(i) + noise) / float(steps);
        float t = tMax * f * f;
        vec2 uv = mix(s0.xy, s1.xy, t);
        float rayZ = 1.0 / mix(k0, k1, t);
        float sd = rtxFinalDepth(uv);
        if (sd < RTX_SKY_DEPTH) {
            float sceneZ = -rtxViewPos(uv, sd).z;
            float delta = rayZ - sceneZ;
            float thickness = max(abs(rayZ - prevZ) * 2.0, 0.3 + rayZ * 0.02);
            if (delta > 0.03 && delta < thickness) {
                float lo = prevT;
                float hi = t;
                for (int j = 0; j < 5; j++) {
                    float mid = 0.5 * (lo + hi);
                    vec2 muv = mix(s0.xy, s1.xy, mid);
                    float mz = 1.0 / mix(k0, k1, mid);
                    float msz = -rtxViewPos(muv, rtxFinalDepth(muv)).z;
                    if (mz - msz > 0.0) {
                        hi = mid;
                    } else {
                        lo = mid;
                    }
                }
                hitUV = mix(s0.xy, s1.xy, hi);
                float edge = min(min(hitUV.x, 1.0 - hitUV.x), min(hitUV.y, 1.0 - hitUV.y));
                return smoothstep(0.0, 0.06, edge) * (1.0 - smoothstep(0.7, 1.0, hi / tMax));
            }
        }
        prevT = t;
        prevZ = rayZ;
    }
    return 0.0;
}

void main() {
    vec2 uv = rtxSnapUV(texCoord);
    float fd = rtxFinalDepth(uv);
    if (fd >= RTX_SKY_DEPTH) {
        fragColor = vec4(0.0);
        return;
    }
    vec3 p = rtxViewPos(uv, fd);
    float dist = length(p);
    float fade = 1.0 - smoothstep(ReflParams2.z * 0.6, ReflParams2.z * 0.95, dist);
    if (fade <= 0.0) {
        fragColor = vec4(0.0);
        return;
    }

    float od = rtxOpaqueDepth(uv);
    bool translucent = od >= RTX_SKY_DEPTH || (-rtxViewPos(uv, od).z) - (-p.z) > 0.05;

    vec3 n = rtxNormal(uv, p, false);
    vec3 nw = rtxSnapWorldNormal(rtxToWorldDir(n));
    vec3 wp = rtxToWorldDir(p) + CameraPos;

    float f0 = 0.04;
    float strength = 0.0;
    bool water = false;
    if (translucent) {
        bool cloud = wp.y > ReflParams2.y - 1.0 && wp.y < ReflParams2.y + 5.0;
        if (!cloud) {
            if (nw.y > 0.8) {
                water = true;
                f0 = 0.08;
                strength = ReflToggles.x;
            } else {
                f0 = 0.06;
                strength = ReflToggles.y;
            }
        }
    } else if (ReflParams.z > 0.001 && nw.y > 0.9) {
        float puddle = smoothstep(0.45, 0.75, valueNoise(wp.xz * 0.35) * 0.7 + valueNoise(wp.xz * 1.3) * 0.3);
        f0 = 0.04;
        strength = ReflParams.z * ReflToggles.z * mix(0.2, 1.0, puddle);
    }
    if (strength <= 0.001) {
        fragColor = vec4(0.0);
        return;
    }

    n = rtxToViewDir(nw);
    vec3 v = normalize(p);
    vec3 nDetail = n;
    vec2 slope = vec2(0.0);
    if (water && ReflParams2.w > 0.5) {
        slope = waveSlope(wp.xz, ReflParams2.x);
        nDetail = rtxToViewDir(normalize(vec3(-slope.x, 1.0, -slope.y)));
    }

    float noise = rtxNoise(gl_FragCoord.xy);
    vec3 r = reflect(v, n);
    vec2 hitUV;
    float conf = traceReflection(p + n * 0.02, r, noise, hitUV);

    vec3 rDetail = reflect(v, nDetail);
    vec3 rw = rtxToWorldDir(rDetail);
    rw.y = abs(rw.y);
    vec3 sky = skyReflection(rw);

    vec3 col = sky;
    if (conf > 0.0) {
        // Distort the traced hit with the wave slope instead of tracing noisy rays.
        vec2 uv = clamp(hitUV + vec2(slope.x, -slope.y) * 0.04, vec2(0.0), vec2(1.0));
        col = mix(sky, texture(SceneSampler, uv).rgb, conf);
    }
    float visibility = mix(ReflParams.w, 1.0, conf);

    float ndv = clamp(dot(-v, nDetail), 0.0, 1.0);
    float fresnel = f0 + (1.0 - f0) * pow(1.0 - ndv, 5.0);
    float weight = clamp(strength * fresnel * visibility * fade, 0.0, 1.0);
    fragColor = vec4(col, weight);
}
