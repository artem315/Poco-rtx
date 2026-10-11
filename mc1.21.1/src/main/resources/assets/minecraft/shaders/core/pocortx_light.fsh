#version 150

#moj_import <pocortx_common.glsl>

// Screen-space ray traced lighting, rendered at reduced resolution.
// Output: r = ambient occlusion, g = direct light visibility (sun/moon shadow),
//         b = light shaft intensity, a = 1.

uniform vec3 LightDir;      // view space, towards the sun or moon
uniform vec4 LightParams;   // x: light strength, y: AO radius (blocks), z: shadow ray length (blocks)
uniform vec4 Counts;        // x: AO samples, y: shadow steps, z: light shaft steps
uniform vec4 Toggles;       // x: AO, y: shadows, z: light shafts (1 = on)

in vec2 texCoord;

out vec4 fragColor;

float computeAO(vec3 p, vec3 n, float noise) {
    int count = int(Counts.x + 0.5);
    float radius = LightParams.y;
    vec3 helper = abs(n.y) < 0.95 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    vec3 t = normalize(cross(helper, n));
    vec3 b = cross(n, t);
    float occlusion = 0.0;
    float total = 0.0;
    for (int i = 0; i < 16; i++) {
        if (i >= count) {
            break;
        }
        float fi = (float(i) + noise) / float(count);
        float ang = (float(i) + noise) * 2.39996323 + noise * 6.2831853;
        float r = sqrt(fi);
        vec2 disk = vec2(cos(ang), sin(ang)) * r;
        float h = max(sqrt(max(1.0 - fi, 0.0)), 0.2);
        float len = radius * mix(0.2, 1.0, fi);
        vec3 s = p + (t * disk.x + b * disk.y + n * h) * len;
        total += 1.0;
        if (s.z > -0.05) {
            continue;
        }
        vec3 sp = rtxProject(s);
        if (!rtxOnScreen(sp.xy)) {
            continue;
        }
        float sd = rtxOpaqueDepth(sp.xy);
        if (sd >= RTX_SKY_DEPTH) {
            continue;
        }
        vec3 sceneP = rtxViewPos(sp.xy, sd);
        // Positive when the visible surface is closer to the camera than the sample.
        // The bias grows with distance so grazing ground far away does not self-occlude.
        float inFront = sceneP.z - s.z;
        if (inFront > 0.03 - p.z * 0.004) {
            occlusion += 1.0 - smoothstep(radius, radius * 2.5, length(sceneP - p));
        }
    }
    return total > 0.0 ? 1.0 - occlusion / total : 1.0;
}

float traceShadow(vec3 p, vec3 n, float noise) {
    float ndl = dot(n, LightDir);
    // Faces turned away from the light only get a partial darkening: vanilla
    // face shading already darkens them and full strength looks too harsh.
    float facing = mix(0.3, 1.0, smoothstep(-0.05, 0.25, ndl));
    if (ndl <= 0.0) {
        return facing;
    }
    int steps = int(Counts.y + 0.5);
    float maxDist = LightParams.z;
    vec3 origin = p + n * (0.03 - p.z * 0.0015);
    for (int i = 0; i < 48; i++) {
        if (i >= steps) {
            break;
        }
        float f = (float(i) + noise) / float(steps);
        float t = 0.06 + f * f * maxDist;
        vec3 s = origin + LightDir * t;
        if (s.z > -0.06) {
            break;
        }
        vec3 sp = rtxProject(s);
        if (!rtxOnScreen(sp.xy)) {
            break;
        }
        float sd = rtxOpaqueDepth(sp.xy);
        if (sd >= RTX_SKY_DEPTH) {
            continue;
        }
        float sceneDist = -rtxViewPos(sp.xy, sd).z;
        float delta = -s.z - sceneDist;
        float thickness = 0.35 + t * 0.12;
        if (delta > 0.02 && delta < thickness) {
            return 0.0;
        }
    }
    return facing;
}

float lightShafts(vec2 uv, float noise) {
    vec4 c = RtxProj * vec4(LightDir, 0.0);
    if (c.w <= 0.0) {
        return 0.0;
    }
    vec2 lightUV = c.xy / c.w * 0.5 + 0.5;
    vec2 delta = lightUV - uv;
    float aspect = RtxScreen.x / RtxScreen.y;
    float dist = length(delta * vec2(aspect, 1.0));
    float falloff = exp(-dist * 2.4);
    if (falloff < 0.02) {
        return 0.0;
    }
    int steps = int(Counts.z + 0.5);
    float len = length(delta);
    vec2 dir = delta / max(len, 1e-5);
    vec2 stepv = dir * min(len, 0.6) / float(steps);
    vec2 pos = uv + stepv * noise;
    float sum = 0.0;
    float w = 1.0;
    float wsum = 0.0;
    for (int i = 0; i < 48; i++) {
        if (i >= steps) {
            break;
        }
        vec2 q = clamp(pos, vec2(0.0), vec2(1.0));
        sum += (rtxFinalDepth(q) >= RTX_SKY_DEPTH ? 1.0 : 0.0) * w;
        wsum += w;
        w *= 0.95;
        pos += stepv;
    }
    return wsum > 0.0 ? sum / wsum * falloff : 0.0;
}

void main() {
    vec2 uv = rtxSnapUV(texCoord);
    float noise = rtxNoise(gl_FragCoord.xy);
    float shafts = (Toggles.z > 0.5 && LightParams.x > 0.001) ? lightShafts(uv, noise) : 0.0;

    float depth = rtxOpaqueDepth(uv);
    if (depth >= RTX_SKY_DEPTH) {
        fragColor = vec4(1.0, 1.0, shafts, 1.0);
        return;
    }

    vec3 p = rtxViewPos(uv, depth);
    vec3 n = rtxNormal(uv, p, true);
    n = rtxToViewDir(rtxSnapWorldNormal(rtxToWorldDir(n)));

    float ao = Toggles.x > 0.5 ? computeAO(p, n, noise) : 1.0;
    float lit = (Toggles.y > 0.5 && LightParams.x > 0.001) ? traceShadow(p, n, noise) : 1.0;

    fragColor = vec4(ao, lit, shafts, 1.0);
}
