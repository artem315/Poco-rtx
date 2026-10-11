#version 330

#moj_import <pocortx:rt_post.glsl>

// Depth-aware 3x3 a-trous blur of a trace buffer (step VARIANT texels). Two passes with
// steps 1 and 2 remove the remaining noise of the temporally accumulated rays without
// bleeding across silhouettes. Alpha carries data (depth, sky light) and is kept.

uniform sampler2D SourceSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / RtxScreen.zw;
    float stepSize = float(VARIANT);
    vec4 center = texture(SourceSampler, texCoord);
    float cz = rtxOpaqueLinear(texCoord);
    vec4 sum = center;
    float wsum = 1.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if (x == 0 && y == 0) {
                continue;
            }
            vec2 uv = texCoord + vec2(float(x), float(y)) * texel * stepSize;
            float z = rtxOpaqueLinear(uv);
            float w = (x == 0 || y == 0 ? 0.6 : 0.4) / (1.0 + 40.0 * abs(z - cz) / max(cz, 0.05));
            sum += texture(SourceSampler, uv) * w;
            wsum += w;
        }
    }
    fragColor = vec4((sum / wsum).rgb, center.a);
}
