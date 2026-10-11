#version 150

#moj_import <pocortx_common.glsl>

// Full resolution composite. Upsamples the traced buffers with depth-aware
// weights and outputs (additive light, multiplier) for the blend
//   result = src.rgb + dst.rgb * src.a
// so the main framebuffer is never sampled while it is being written.

uniform sampler2D LightSampler;
uniform sampler2D ReflSampler;

uniform vec2 TraceSize;
uniform vec3 SunColor;
uniform vec4 Strength;      // x: AO, y: shadow (already scaled by light strength), z: reflections, w: light shafts
uniform vec4 Misc;          // x: debug view, y: fade distance

in vec2 texCoord;

out vec4 fragColor;

float linearDist(vec2 uv, float depth) {
    return depth >= RTX_SKY_DEPTH ? 100000.0 : -rtxViewPos(uv, depth).z;
}

float depthWeight(float a, float b) {
    return 1.0 / (1.0 + 60.0 * abs(a - b) / max(b, 0.05));
}

void main() {
    float od = rtxOpaqueDepth(texCoord);
    float fd = rtxFinalDepth(texCoord);
    float oz = linearDist(texCoord, od);
    float fz = linearDist(texCoord, fd);

    vec2 coord = texCoord * TraceSize - 0.5;
    vec2 base = floor(coord);
    vec2 fr = coord - base;

    vec4 light = vec4(0.0);
    vec4 refl = vec4(0.0);
    float lightW = 0.0;
    float reflW = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 off = vec2(float(i == 1 || i == 3), float(i >= 2));
        vec2 tuv = (base + off + 0.5) / TraceSize;
        float bw = (off.x > 0.5 ? fr.x : 1.0 - fr.x) * (off.y > 0.5 ? fr.y : 1.0 - fr.y) + 1e-4;
        float lw = bw * depthWeight(linearDist(tuv, rtxOpaqueDepth(tuv)), oz);
        float rw = bw * depthWeight(linearDist(tuv, rtxFinalDepth(tuv)), fz);
        light += texture(LightSampler, tuv) * lw;
        refl += texture(ReflSampler, tuv) * rw;
        lightW += lw;
        reflW += rw;
    }
    light /= max(lightW, 1e-6);
    refl /= max(reflW, 1e-6);

    float fade = 1.0 - smoothstep(Misc.y * 0.55, Misc.y * 0.95, oz);
    float ao = mix(1.0, light.r, Strength.x * fade);
    float shadow = 1.0 - Strength.y * fade * (1.0 - light.g);
    float shade = ao * shadow;
    if (fz < oz - 0.05) {
        // Water / stained glass in front: the surface itself is lit, only the
        // geometry behind it is occluded, so soften the darkening.
        shade = mix(1.0, shade, 0.5);
    }

    float reflWeight = clamp(refl.a * Strength.z, 0.0, 1.0);
    vec3 add = refl.rgb * reflWeight + SunColor * (light.b * Strength.w);

    int debugView = int(Misc.x + 0.5);
    if (debugView == 1) {
        fragColor = vec4(vec3(light.r), 0.0);
    } else if (debugView == 2) {
        fragColor = vec4(vec3(light.g), 0.0);
    } else if (debugView == 3) {
        fragColor = vec4(refl.rgb * reflWeight, 0.0);
    } else if (debugView == 4) {
        fragColor = vec4(vec3(light.b), 0.0);
    } else {
        fragColor = vec4(add, shade * (1.0 - reflWeight));
    }
}
