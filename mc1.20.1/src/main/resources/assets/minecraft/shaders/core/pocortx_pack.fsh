#version 150

// Copies a depth texture into a plain RGBA8 texture (24-bit fixed point).
// Depth-to-depth blits are fragile on mobile GL translators; a shader pass
// into a colour target works everywhere vanilla post effects work.
precision highp float;
precision highp sampler2D;
precision highp sampler2DShadow;

uniform sampler2D DepthSampler;

// LTW (MJ Launcher's GL -> GLES wrapper) declares every 2D sampler lowp, which is
// fp16 on Mali and far too coarse for depth, unless the shader uses a shadow
// sampler. This never-taken branch keeps one in the shader so depth stays highp.
uniform sampler2DShadow PrecisionHintSampler;
uniform float PrecisionHint;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float d = clamp(texture(DepthSampler, texCoord).r, 0.0, 1.0);
    // 8 + 8 + 8 bits using only small constants: translators may re-print float
    // literals with 7 significant digits, which would corrupt 16777215.0.
    float x = d * 255.0;
    float r = floor(x);
    x = (x - r) * 256.0;
    float g = floor(x);
    x = (x - g) * 256.0;
    float b = floor(x);
    // +0.25 keeps the byte exact whether the driver rounds or truncates.
    vec4 outColor = vec4(r + 0.25, g + 0.25, b + 0.25, 255.0) / 255.0;
    if (PrecisionHint > 0.5) {
        outColor.a = texture(PrecisionHintSampler, vec3(texCoord, 0.5));
    }
    fragColor = outColor;
}
