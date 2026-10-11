#version 330

#moj_import <pocortx:fx_common.glsl>

// Bloom downsample: four bilinear taps (a 4x4 box). The first level (VARIANT) also keeps
// only the bright part of the image with a soft knee.

uniform sampler2D SourceSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 ts = 1.0 / vec2(textureSize(SourceSampler, 0));
    vec3 c = texture(SourceSampler, texCoord + ts * vec2(-1.0, -1.0)).rgb
           + texture(SourceSampler, texCoord + ts * vec2(1.0, -1.0)).rgb
           + texture(SourceSampler, texCoord + ts * vec2(-1.0, 1.0)).rgb
           + texture(SourceSampler, texCoord + ts * vec2(1.0, 1.0)).rgb;
    c *= 0.25;
#ifdef VARIANT
    float l = max(c.r, max(c.g, c.b));
    float threshold = FxBloom.y;
    float knee = 0.2;
    float soft = clamp(l - threshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee + 1e-4);
    c *= max(soft, l - threshold) / max(l, 1e-4);
#endif
    fragColor = vec4(c, 1.0);
}
