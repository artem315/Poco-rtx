#version 330

#moj_import <pocortx:rt_post.glsl>

// Full resolution composite of the ray traced buffers over a copy of the frame: replaces the
// vanilla lighting with ray traced lighting, then adds reflections, highlights and volumetric
// light. Sun/moon N.L and vanilla face shading are evaluated per pixel from the depth buffer;
// the trace resolution buffers are upsampled with depth and normal aware weights, so light
// never bleeds from one block face onto the next.

uniform sampler2D SceneSampler;     // the frame so far
uniform sampler2D FinalDepthSampler;
uniform sampler2D LightSampler;     // AO, sun/moon visibility, volumetric light, view depth
uniform sampler2D IndirectSampler;  // bounced light (GI)
uniform sampler2D ReflSampler;      // light to add (reflections, highlights), reflection weight
uniform sampler2D NormalSampler;    // G-buffer: world normal, roughness
uniform sampler2D InfoSampler;      // G-buffer: block light, sky light, emission, material id

in vec2 texCoord;

out vec4 fragColor;

vec3 viewPosAt(ivec2 p) {
    return rtxViewPos((vec2(p) + 0.5) / RtxScreen.xy, texelFetch(OpaqueDepthSampler, p, 0).r);
}

// View space normal of the opaque surface under this pixel.
vec3 pixelNormal(ivec2 pix, vec3 p) {
    ivec2 size = ivec2(RtxScreen.xy);
    ivec2 r = min(pix + ivec2(1, 0), size - 1), l = max(pix - ivec2(1, 0), ivec2(0));
    ivec2 u = min(pix + ivec2(0, 1), size - 1), d = max(pix - ivec2(0, 1), ivec2(0));
    vec3 pr = texelFetch(OpaqueDepthSampler, r, 0).r > 0.0000001 ? viewPosAt(r) : p;
    vec3 pl = texelFetch(OpaqueDepthSampler, l, 0).r > 0.0000001 ? viewPosAt(l) : p;
    vec3 pu = texelFetch(OpaqueDepthSampler, u, 0).r > 0.0000001 ? viewPosAt(u) : p;
    vec3 pd = texelFetch(OpaqueDepthSampler, d, 0).r > 0.0000001 ? viewPosAt(d) : p;
    vec3 dx = length(pr - p) < length(p - pl) ? pr - p : p - pl;
    vec3 dy = length(pu - p) < length(p - pd) ? pu - p : p - pd;
    vec3 n = cross(dx, dy);
    if (dot(n, n) < 1e-14) {
        return -normalize(p);
    }
    n = normalize(n);
    return dot(n, p) > 0.0 ? -n : n;
}

void main() {
    ivec2 pix = ivec2(gl_FragCoord.xy);
    float od = texelFetch(OpaqueDepthSampler, pix, 0).r;
    float fd = texelFetch(FinalDepthSampler, pix, 0).r;
    float oz = rtxLinear(texCoord, od);
    float fz = rtxLinear(texCoord, fd);
    bool sky = od <= 0.0000001;
    vec3 vp = sky ? vec3(0.0, 0.0, -1.0) : rtxViewPos(texCoord, od);
    vec3 nView = sky ? vec3(0.0, 0.0, 1.0) : pixelNormal(pix, vp);

    // Gather the four trace pixels around this one.
    vec2 coord = texCoord * RtxScreen.zw - 0.5;
    vec2 base = floor(coord);
    vec2 fr = coord - base;
    vec4 light = vec4(0.0);
    vec3 gi = vec3(0.0);
    vec4 info = vec4(0.0);
    float wsum = 0.0;
    vec4 refl = vec4(0.0);
    float rsum = 0.0;
    bool behindTranslucent = fz < oz - 0.05;
    for (int i = 0; i < 4; i++) {
        vec2 off = vec2(float(i == 1 || i == 3), float(i >= 2));
        vec2 tuv = (clamp(base + off, vec2(0.0), RtxScreen.zw - 1.0) + 0.5) / RtxScreen.zw;
        float bw = (off.x > 0.5 ? fr.x : 1.0 - fr.x) * (off.y > 0.5 ? fr.y : 1.0 - fr.y) + 1e-3;
        vec4 tLight = texture(LightSampler, tuv);
        vec4 tInfo = texture(InfoSampler, tuv);
        bool valid = int(tInfo.a * 255.0 + 0.5) != RTX_MAT_NONE;
        vec3 tn = (RtxViewRot * vec4(texture(NormalSampler, tuv).xyz, 0.0)).xyz;
        float nw = pow(max(dot(tn, nView), 0.0), 8.0) + 0.002;
        // The light buffer keeps the view depth of each trace pixel's surface in alpha.
        float dw = rtxDepthWeight(tLight.a, oz);
        float w = valid ? bw * dw * nw : 0.0;
        light += tLight * w;
        gi += texture(IndirectSampler, tuv).rgb * w;
        info += tInfo * w;
        wsum += w;
        // Reflections belong to the front surface: water and glass when there is one.
        float rw = bw * (behindTranslucent ? rtxDepthWeight(rtxLinear(rtxSnapUV(tuv), texture(FinalDepthSampler, rtxSnapUV(tuv)).r), fz) : dw);
        refl += texture(ReflSampler, tuv) * rw;
        rsum += rw;
    }
    bool haveRt = wsum > 1e-6 && !sky;
    float volumetric = haveRt ? light.b / wsum : texture(LightSampler, texCoord).b;
    light = haveRt ? light / wsum : vec4(1.0, 1.0, 0.0, 0.0);
    gi = haveRt ? gi / wsum : vec3(0.0);
    info = haveRt ? info / wsum : vec4(0.0, 1.0, 0.0, 0.0);
    refl /= max(rsum, 1e-6);

    // Ray traced lighting relative to the vanilla lighting already in the frame.
    vec3 ratio = vec3(1.0);
    if (haveRt && RtxParams.w > 0.5) {
        vec3 n = normalize(transpose(mat3(RtxViewRot)) * nView);
        vec2 lm = info.rg;
        float vis = mix(1.0, light.g, RtxStrength.y);
        float ao = mix(1.0, light.r, RtxStrength.x);
        bool entity = info.a * 255.0 > float(RTX_MAT_ENTITY) - 0.5 && info.a * 255.0 < float(RTX_MAT_ENTITY) + 0.5;
        vec3 vanilla = max(rtxVanillaLight(lm), vec3(0.02));
        // Bounced light is noisy: in dark places it must not be divided by an almost black
        // vanilla light level, or the noise is amplified many times over.
        ratio = (rtxLight(n, lm, vis, ao) / vanilla + gi * RtxParams.z / max(vanilla, vec3(0.12))) / (entity ? 1.0 : rtxFaceShade(n));
        // Glowing blocks keep at least their vanilla brightness.
        ratio = mix(ratio, max(ratio, vec3(1.0)), clamp(info.b, 0.0, 1.0));
        float fade = 1.0 - smoothstep(RtxParams.y * 0.85, RtxParams.y * 1.15, length(vp));
        ratio = mix(vec3(1.0), ratio, fade);
        if (behindTranslucent) {
            // Water / stained glass in front: its own colour is not relit, only what is behind.
            ratio = mix(vec3(1.0), ratio, 0.6);
        }
    }
    ratio = clamp(ratio, 0.0, 3.0);
    float reflWeight = clamp(refl.a, 0.0, 1.0) * RtxStrength.w;
    vec3 add = refl.rgb * RtxStrength.w + RtxLightColor.rgb * volumetric * RtxStrength.z;

    int debugView = int(RtxParams.x + 0.5);
    vec3 debugColor = vec3(0.0);
    if (debugView == 1) {
        debugColor = vec3(light.r);
    } else if (debugView == 2) {
        debugColor = vec3(light.g * max(dot(nView, (RtxViewRot * vec4(RtxLightDir.xyz, 0.0)).xyz), 0.0));
    } else if (debugView == 3) {
        debugColor = gi;
    } else if (debugView == 4) {
        debugColor = refl.rgb * RtxStrength.w;
    } else if (debugView == 5) {
        debugColor = vec3(volumetric * 4.0);
    } else if (debugView == 6) {
        debugColor = ratio * 0.5;
    }

    vec3 scene = texelFetch(SceneSampler, pix, 0).rgb;
    fragColor = vec4(debugView != 0 ? debugColor : scene * ratio * (1.0 - reflWeight) + add, 1.0);
}
