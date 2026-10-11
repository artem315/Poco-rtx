#ifndef POCORTX_FX_COMMON_GLSL
#define POCORTX_FX_COMMON_GLSL

// Shader effects (BSL-style look), own implementation.

layout(std140) uniform FxFrame {
    vec4 FxGrade;   // x: exposure, y: saturation, z: vibrance, w: contrast
    vec4 FxGrade2;  // x: temperature, y: vignette, z: sharpen, w: tonemap on
    vec4 FxBloom;   // x: strength, y: threshold
    vec4 FxScreen;  // xy: size, zw: 1 / size
};

#endif
