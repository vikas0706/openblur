package io.github.vikas0706.openblur.render

/**
 * GLSL ES 1.00 sources, so one set runs under both ES 2.0 and ES 3.0 contexts. No uniform is shared between a
 * vertex and a fragment shader, because their default float precisions differ and a shared uniform must match.
 */
internal object MaskShaders {
    // RGBA16F frames need more than the lowp a sampler gets by default.
    private const val FRAGMENT_PRECISION = """
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
precision highp sampler2D;
#else
precision mediump float;
precision mediump sampler2D;
#endif
"""

    const val COPY_VERTEX = """
attribute vec2 aCorner;
varying vec2 vUv;
void main() {
  vUv = aCorner;
  gl_Position = vec4(aCorner * 2.0 - 1.0, 0.0, 1.0);
}
"""

    const val COPY_FRAGMENT = FRAGMENT_PRECISION + """
uniform sampler2D uFrame;
varying vec2 vUv;
void main() {
  gl_FragColor = texture2D(uFrame, vUv);
}
"""

    // The four taps are varyings so the fragment shader samples at interpolated coordinates directly,
    // which keeps full precision on GPUs whose fragment arithmetic is 16 bit.
    const val DOWNSAMPLE_VERTEX = """
attribute vec2 aCorner;
uniform vec2 uTap;
varying vec2 vTap0;
varying vec2 vTap1;
varying vec2 vTap2;
varying vec2 vTap3;
void main() {
  vTap0 = aCorner - uTap;
  vTap1 = aCorner + vec2(uTap.x, -uTap.y);
  vTap2 = aCorner + vec2(-uTap.x, uTap.y);
  vTap3 = aCorner + uTap;
  gl_Position = vec4(aCorner * 2.0 - 1.0, 0.0, 1.0);
}
"""

    // Bilinear taps 0.75 source texels from the centre weigh the 4 x 4 texels under them 1 3 3 1 on each
    // axis: a binomial low-pass with no response at the source's Nyquist frequency.
    const val DOWNSAMPLE_FRAGMENT = FRAGMENT_PRECISION + """
uniform sampler2D uSource;
varying vec2 vTap0;
varying vec2 vTap1;
varying vec2 vTap2;
varying vec2 vTap3;
void main() {
  gl_FragColor = 0.25 * (texture2D(uSource, vTap0) + texture2D(uSource, vTap1)
      + texture2D(uSource, vTap2) + texture2D(uSource, vTap3));
}
"""

    const val MASK_VERTEX = """
attribute vec2 aCorner;
uniform vec4 uQuad;
uniform vec2 uCentre;
uniform vec4 uToUnit;
varying vec2 vUv;
varying vec2 vUnit;
void main() {
  vec2 position = mix(uQuad.xy, uQuad.zw, aCorner);
  vec2 uv = position * 0.5 + 0.5;
  vec2 offset = uv - uCentre;
  vUv = uv;
  vUnit = vec2(dot(uToUnit.xy, offset), dot(uToUnit.zw, offset));
  gl_Position = vec4(position, 0.0, 1.0);
}
"""

    // uShape is the half size in units of its smaller side, then the soft edge and corner radius in that unit.
    // Depth is the distance inward from the outline; the ellipse uses the first-order estimate
    // (1 - f) / |grad f|, which is exact at the outline, the only place the soft edge reads it.
    private const val COVERAGE = """
uniform vec4 uShape;
uniform float uOval;
varying vec2 vUnit;
float coverage() {
  float depth;
  if (uOval > 0.5) {
    float f = length(vUnit);
    if (f < 0.5) {
      return 1.0;
    }
    depth = (1.0 - f) * f / length(vUnit / uShape.xy);
  } else {
    vec2 p = (abs(vUnit) - 1.0) * uShape.xy + uShape.w;
    depth = uShape.w - length(max(p, 0.0)) - min(max(p.x, p.y), 0.0);
  }
  return smoothstep(0.0, uShape.z, depth);
}
"""

    // Cubic B-spline reconstruction from four bilinear taps. Unlike plain bilinear upsampling it has a
    // continuous second derivative, so a magnified level shows no texel grid or diamond highlights.
    const val BLUR_FRAGMENT = FRAGMENT_PRECISION + COVERAGE + """
uniform sampler2D uLo;
uniform sampler2D uHi;
uniform vec4 uLoSize;
uniform vec4 uHiSize;
uniform float uLevelMix;
varying vec2 vUv;
vec4 smoothSample(sampler2D level, vec2 uv, vec4 size) {
  vec2 st = uv * size.xy - 0.5;
  vec2 base = floor(st);
  vec2 f = st - base;
  vec2 f2 = f * f;
  vec2 f3 = f2 * f;
  vec2 w0 = (1.0 - 3.0 * f + 3.0 * f2 - f3) / 6.0;
  vec2 w1 = (4.0 - 6.0 * f2 + 3.0 * f3) / 6.0;
  vec2 w3 = f3 / 6.0;
  vec2 g0 = w0 + w1;
  vec2 g1 = 1.0 - g0;
  vec2 p0 = (base - 0.5 + w1 / g0) * size.zw;
  vec2 p1 = (base + 1.5 + w3 / g1) * size.zw;
  return g0.y * (g0.x * texture2D(level, p0) + g1.x * texture2D(level, vec2(p1.x, p0.y)))
      + g1.y * (g0.x * texture2D(level, vec2(p0.x, p1.y)) + g1.x * texture2D(level, p1));
}
void main() {
  float alpha = coverage();
  if (alpha <= 0.0) {
    discard;
  }
  vec4 color = mix(smoothSample(uLo, vUv, uLoSize), smoothSample(uHi, vUv, uHiSize), uLevelMix);
  gl_FragColor = vec4(color.rgb, alpha);
}
"""

    // Every fragment of a block computes the same block centre, so the block comes out one flat colour.
    const val MOSAIC_FRAGMENT = FRAGMENT_PRECISION + COVERAGE + """
uniform sampler2D uLo;
uniform sampler2D uHi;
uniform float uLevelMix;
uniform vec3 uGrid;
uniform vec2 uGridOrigin;
uniform vec4 uBlockStep;
void main() {
  float alpha = coverage();
  if (alpha <= 0.0) {
    discard;
  }
  vec2 block = floor(vUnit * uGrid.xy + uGrid.z) + 0.5 - uGrid.z;
  vec2 uv = uGridOrigin + block.x * uBlockStep.xy + block.y * uBlockStep.zw;
  vec4 color = mix(texture2D(uLo, uv), texture2D(uHi, uv), uLevelMix);
  gl_FragColor = vec4(color.rgb, alpha);
}
"""

    const val SOLID_FRAGMENT = FRAGMENT_PRECISION + COVERAGE + """
uniform vec3 uColor;
void main() {
  float alpha = coverage();
  if (alpha <= 0.0) {
    discard;
  }
  gl_FragColor = vec4(uColor, alpha);
}
"""
}
