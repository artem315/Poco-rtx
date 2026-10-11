#version 150

// Copies a depth texture into a plain RGBA8 texture (24-bit fixed point).
// Depth-to-depth blits are fragile on mobile GL translators; a shader pass
// into a colour target works everywhere vanilla post effects work.
precision highp float;
precision highp sampler2D;

uniform sampler2D DepthSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    float d = clamp(texture(DepthSampler, texCoord).r, 0.0, 1.0);
    // 16777215.5 is not representable in fp32 and rounds up to 2^24, which
    // would wrap the sky (depth 1.0) into a near depth; clamp to 24 bits.
    float v = min(floor(d * 16777215.0 + 0.5), 16777215.0);
    float r = floor(v / 65536.0);
    v -= r * 65536.0;
    float g = floor(v / 256.0);
    float b = v - g * 256.0;
    // +0.25 keeps the byte exact whether the driver rounds or truncates.
    fragColor = vec4(r + 0.25, g + 0.25, b + 0.25, 255.0) / 255.0;
}
