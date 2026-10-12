#version 330
#extension GL_ARB_separate_shader_objects : require

#include <pocortx:rt_post.glsl>

// Depth and normal aware 3x3 a-trous blur of a trace buffer (step VARIANT texels). Two
// passes with steps 1 and 2 remove the remaining noise of the temporally accumulated rays
// without bleeding across silhouettes or from one block face onto the next. Alpha carries
// data (depth) and is kept.

uniform sampler2D SourceSampler;
uniform sampler2D NormalSampler;    // G-buffer: world normal
uniform sampler2D InfoSampler;      // G-buffer: material id in alpha

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

bool hasSurface(vec2 uv) {
    return int(texture(InfoSampler, uv).a * 255.0 + 0.5) != RTX_MAT_NONE;
}

void main() {
    vec2 texel = 1.0 / RtxScreen.zw;
    float stepSize = float(VARIANT);
    vec4 center = texture(SourceSampler, texCoord);
    float cz = rtxOpaqueLinear(texCoord);
    vec3 cn = texture(NormalSampler, texCoord).xyz;
    vec4 sum = center;
    float wsum = 1.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if (x == 0 && y == 0) {
                continue;
            }
            vec2 uv = texCoord + vec2(float(x), float(y)) * texel * stepSize;
            if (!hasSurface(uv)) {
                continue;
            }
            float z = rtxOpaqueLinear(uv);
            float nw = pow(max(dot(texture(NormalSampler, uv).xyz, cn), 0.0), 16.0);
            float w = (x == 0 || y == 0 ? 0.6 : 0.4) * nw / (1.0 + 40.0 * abs(z - cz) / max(cz, 0.05));
            sum += texture(SourceSampler, uv) * w;
            wsum += w;
        }
    }
    fragColor = vec4((sum / wsum).rgb, center.a);
}
