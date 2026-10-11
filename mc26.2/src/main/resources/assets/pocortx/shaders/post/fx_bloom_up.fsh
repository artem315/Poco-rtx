#version 330

#moj_import <pocortx:fx_common.glsl>

// Bloom upsample: 3x3 tent filter of the smaller level, added onto the larger one.

uniform sampler2D SourceSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 ts = 1.0 / vec2(textureSize(SourceSampler, 0));
    vec3 c = texture(SourceSampler, texCoord).rgb * 4.0;
    c += (texture(SourceSampler, texCoord + vec2(ts.x, 0.0)).rgb + texture(SourceSampler, texCoord - vec2(ts.x, 0.0)).rgb
        + texture(SourceSampler, texCoord + vec2(0.0, ts.y)).rgb + texture(SourceSampler, texCoord - vec2(0.0, ts.y)).rgb) * 2.0;
    c += texture(SourceSampler, texCoord + ts).rgb + texture(SourceSampler, texCoord - ts).rgb
       + texture(SourceSampler, texCoord + vec2(ts.x, -ts.y)).rgb + texture(SourceSampler, texCoord + vec2(-ts.x, ts.y)).rgb;
    fragColor = vec4(c / 16.0, 1.0);
}
