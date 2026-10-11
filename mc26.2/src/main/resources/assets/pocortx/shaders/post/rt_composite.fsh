#version 330

#moj_import <pocortx:rt_post.glsl>

// Full resolution composite of the ray traced buffers. Upsamples them with depth-aware
// weights and outputs (additive light, multiplier) for the blend
//   result = src.rgb + dst.rgb * src.a
// so the main framebuffer is never sampled while it is being written.

uniform sampler2D FinalDepthSampler;
uniform sampler2D LightSampler;     // AO, sun visibility, volumetric light, view depth
uniform sampler2D IndirectSampler;  // GI + specular (rgb)
uniform sampler2D ReflSampler;      // reflection colour (premultiplied by its weight), weight

in vec2 texCoord;

out vec4 fragColor;

float depthWeight(float a, float b) {
    return 1.0 / (1.0 + 60.0 * abs(a - b) / max(b, 0.05));
}

void main() {
    float od = texture(OpaqueDepthSampler, texCoord).r;
    float fd = texture(FinalDepthSampler, texCoord).r;
    float oz = rtxLinear(texCoord, od);
    float fz = rtxLinear(texCoord, fd);

    vec2 coord = texCoord * RtxScreen.zw - 0.5;
    vec2 base = floor(coord);
    vec2 fr = coord - base;
    vec4 light = vec4(0.0);
    vec4 indirect = vec4(0.0);
    float wsum = 0.0;
    for (int i = 0; i < 4; i++) {
        vec2 off = vec2(float(i == 1 || i == 3), float(i >= 2));
        vec2 tuv = (base + off + 0.5) / RtxScreen.zw;
        float bw = (off.x > 0.5 ? fr.x : 1.0 - fr.x) * (off.y > 0.5 ? fr.y : 1.0 - fr.y) + 1e-4;
        float w = bw * depthWeight(rtxOpaqueLinear(tuv), oz);
        light += texture(LightSampler, tuv) * w;
        indirect += texture(IndirectSampler, tuv) * w;
        wsum += w;
    }
    light /= max(wsum, 1e-6);
    indirect /= max(wsum, 1e-6);
    vec4 refl = texture(ReflSampler, texCoord);

    float ao = mix(1.0, light.r, RtxStrength.x);
    float s = RtxStrength.y;
    float shade = ao * mix(1.0 - s, 1.0 + 0.12 * s, light.g);
    vec3 add = indirect.rgb;
    if (fz < oz - 0.05) {
        // Water / stained glass in front: the surface itself is lit, only what is
        // behind it is shaded, so soften the effect.
        shade = mix(1.0, shade, 0.5);
        add *= 0.6;
    }
    add += RtxLightColor.rgb * light.b * RtxStrength.z;
    float reflWeight = clamp(refl.a, 0.0, 1.0) * RtxStrength.w;
    vec3 reflColor = refl.rgb * RtxStrength.w;

    int debugView = int(RtxParams.x + 0.5);
    if (debugView == 1) {
        fragColor = vec4(vec3(light.r), 0.0);
    } else if (debugView == 2) {
        fragColor = vec4(vec3(light.g), 0.0);
    } else if (debugView == 3) {
        fragColor = vec4(indirect.rgb * 2.0, 0.0);
    } else if (debugView == 4) {
        fragColor = vec4(reflColor, 0.0);
    } else if (debugView == 5) {
        fragColor = vec4(vec3(light.b * 4.0), 0.0);
    } else {
        fragColor = vec4(add + reflColor, shade * (1.0 - reflWeight));
    }
}
