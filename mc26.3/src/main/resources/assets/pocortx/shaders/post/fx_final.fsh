#version 330
#extension GL_ARB_separate_shader_objects : require

#include <pocortx:fx_common.glsl>

// Final shader pass: sharpening, bloom, exposure, white balance, highlight roll-off,
// contrast, saturation/vibrance and vignette.

uniform sampler2D SceneSampler;
uniform sampler2D BloomSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

vec3 rolloff(vec3 x) {
    // Extended Reinhard with white point 1.2, normalised so 1.0 stays 1.0.
    const float w2 = 1.44;
    vec3 y = x * (1.0 + x / w2) / (1.0 + x);
    return y / ((1.0 + 1.0 / w2) / 2.0);
}

void main() {
    vec3 c = texture(SceneSampler, texCoord).rgb;
    float sharpen = FxGrade2.z;
    if (sharpen > 0.0) {
        vec2 px = FxScreen.zw;
        vec3 n = texture(SceneSampler, texCoord + vec2(px.x, 0.0)).rgb + texture(SceneSampler, texCoord - vec2(px.x, 0.0)).rgb
               + texture(SceneSampler, texCoord + vec2(0.0, px.y)).rgb + texture(SceneSampler, texCoord - vec2(0.0, px.y)).rgb;
        c = clamp(c + (c - n * 0.25) * sharpen * 0.8, 0.0, 1.0);
    }
    vec3 lin = pow(c, vec3(2.2));
    vec3 bloom = texture(BloomSampler, texCoord).rgb;
    lin += pow(bloom, vec3(2.2)) * FxBloom.x * 0.6;
    lin *= FxGrade.x;
    float t = FxGrade2.x;
    lin *= vec3(1.0 + 0.12 * t, 1.0 + 0.02 * t, 1.0 - 0.12 * t);
    if (FxGrade2.w > 0.5) {
        lin = rolloff(lin);
    }
    vec3 col = pow(clamp(lin, 0.0, 1.0), vec3(1.0 / 2.2));
    col = (col - 0.5) * FxGrade.w + 0.5;
    float l = dot(col, vec3(0.2126, 0.7152, 0.0722));
    float chroma = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
    col = mix(vec3(l), col, FxGrade.y + FxGrade.z * (1.0 - chroma));
    vec2 d = texCoord - 0.5;
    d.x *= FxScreen.x / FxScreen.y;
    col *= 1.0 - FxGrade2.y * 0.8 * smoothstep(0.35, 1.1, length(d));
    fragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
