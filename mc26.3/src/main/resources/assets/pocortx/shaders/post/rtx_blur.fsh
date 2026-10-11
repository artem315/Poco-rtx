#version 330
#extension GL_ARB_separate_shader_objects : require

#include <pocortx:rtx_common.glsl>

// Depth-aware 3x3 blur of the low resolution lighting buffer. Removes the
// per-pixel noise pattern of the short ray marches without bleeding AO or
// shadows across silhouettes.

uniform sampler2D LightSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

float linearDist(vec2 uv) {
    uv = rtxSnapUV(uv);
    float d = rtxOpaqueDepth(uv);
    return rtxIsSky(d) ? 100000.0 : -rtxViewPos(uv, d).z;
}

void main() {
    vec2 texel = 1.0 / RtxScreen.zw;
    float center = linearDist(texCoord);
    vec4 sum = vec4(0.0);
    float wsum = 0.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            vec2 uv = texCoord + vec2(float(x), float(y)) * texel;
            float spatial = (x == 0 ? 2.0 : 1.0) * (y == 0 ? 2.0 : 1.0);
            float z = linearDist(uv);
            float w = spatial / (1.0 + 80.0 * abs(z - center) / max(center, 0.05));
            sum += texture(LightSampler, uv) * w;
            wsum += w;
        }
    }
    fragColor = sum / max(wsum, 1e-6);
}
