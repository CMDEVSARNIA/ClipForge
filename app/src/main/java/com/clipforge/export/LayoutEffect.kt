package com.clipforge.export

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Renders a [LayoutSpec] into an [outWidth]×[outHeight] frame (1080×1920 for export, smaller for preview).
 *
 * Background: the cover region is downsampled to 1/8 size, blurred with a separable 9-tap Gaussian
 * (several iterations), darkened, then upsampled bilinearly, which is cheap and smooth. Layers: sharp
 * source regions mapped into destination rects with anti-aliased rounded corners.
 *
 * [requestProvider] is read per frame, so the editor preview can switch modes live; the spec is
 * computed from the *actual* input size, so rotation and odd resolutions are handled.
 */
@UnstableApi
class LayoutEffect(
    private val outWidth: Int,
    private val outHeight: Int,
    private val requestProvider: (presentationTimeUs: Long) -> LayoutRequest,
) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LayoutShaderProgram(outWidth, outHeight, useHdr, requestProvider)

    companion object {
        fun constant(outWidth: Int, outHeight: Int, req: LayoutRequest) = LayoutEffect(outWidth, outHeight) { req }
    }
}

@UnstableApi
private class LayoutShaderProgram(
    private val outW: Int,
    private val outH: Int,
    private val useHdr: Boolean,
    private val requestProvider: (Long) -> LayoutRequest,
) : BaseGlShaderProgram(useHdr, /* texturePoolCapacity= */ 1) {

    private val downProgram: GlProgram
    private val blurProgram: GlProgram
    private val compositeProgram: GlProgram
    private val smallW = (outW / 8).coerceAtLeast(8)
    private val smallH = (outH / 8).coerceAtLeast(8)
    private var texA = -1
    private var fboA = -1
    private var texB = -1
    private var fboB = -1
    private var inW = 0
    private var inH = 0
    private var lastReq: LayoutRequest? = null
    private var spec: LayoutSpec? = null
    private val fboBinding = IntArray(1)

    init {
        try {
            downProgram = GlProgram(VERTEX, FRAG_DOWNSAMPLE)
            blurProgram = GlProgram(VERTEX, FRAG_BLUR)
            compositeProgram = GlProgram(VERTEX, FRAG_COMPOSITE)
            for (p in listOf(downProgram, blurProgram, compositeProgram)) {
                p.setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        inW = inputWidth
        inH = inputHeight
        lastReq = null
        try {
            if (texA < 0) {
                texA = GlUtil.createTexture(smallW, smallH, useHdr)
                fboA = GlUtil.createFboForTexture(texA)
                texB = GlUtil.createTexture(smallW, smallH, useHdr)
                fboB = GlUtil.createFboForTexture(texB)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        return Size(outW, outH)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        try {
            val req = requestProvider(presentationTimeUs)
            if (req != lastReq || spec == null) {
                spec = CropMath.spec(req, inW, inH, outW, outH)
                lastReq = req
            }
            val s = spec!!
            // BaseGlShaderProgram focused the output framebuffer; remember it for the final pass.
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, fboBinding, 0)
            val outFbo = fboBinding[0]

            val bg = s.background
            if (bg != null) {
                // 1. Downsample the cover region into A.
                GlUtil.focusFramebufferUsingCurrentContext(fboA, smallW, smallH)
                downProgram.use()
                downProgram.setSamplerTexIdUniform("uTex", inputTexId, 0)
                downProgram.setFloatsUniform("uSrc", floatArrayOf(bg.l, bg.t, bg.r, bg.b))
                // Tap spread ≈ half a small-texture pixel, expressed in input texture units.
                downProgram.setFloatsUniform("uSpread", floatArrayOf(bg.w / smallW * 0.5f, bg.h / smallH * 0.5f))
                downProgram.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                // 2. Separable blur, A→B (horizontal) then B→A (vertical), a few times.
                repeat(BLUR_ITERATIONS) {
                    blurPass(texA, fboB, 1f / smallW, 0f)
                    blurPass(texB, fboA, 0f, 1f / smallH)
                }
            }

            // 3. Composite into the output.
            GlUtil.focusFramebufferUsingCurrentContext(outFbo, outW, outH)
            compositeProgram.use()
            compositeProgram.setSamplerTexIdUniform("uTex", inputTexId, 0)
            compositeProgram.setSamplerTexIdUniform("uBlur", if (texA >= 0) texA else inputTexId, 1)
            compositeProgram.setIntUniform("uHasBg", if (bg != null) 1 else 0)
            compositeProgram.setFloatUniform("uBgDim", s.bgDim)
            compositeProgram.setFloatsUniform("uOutSize", floatArrayOf(outW.toFloat(), outH.toFloat()))
            for (i in 0..1) {
                val l = s.layers.getOrNull(i)
                compositeProgram.setIntUniform("uL${i}On", if (l != null) 1 else 0)
                val src = l?.src ?: NRect.FULL
                val dst = l?.dst ?: NRect.FULL
                compositeProgram.setFloatsUniform("uL${i}Src", floatArrayOf(src.l, src.t, src.r, src.b))
                compositeProgram.setFloatsUniform("uL${i}Dst", floatArrayOf(dst.l, dst.t, dst.r, dst.b))
                compositeProgram.setFloatUniform("uL${i}Radius", (l?.cornerRadiusPx ?: 0f) * outW / CropMath.OUT_W)
            }
            compositeProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GlUtil.checkGlError()
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e, presentationTimeUs)
        }
    }

    private fun blurPass(srcTex: Int, dstFbo: Int, dx: Float, dy: Float) {
        GlUtil.focusFramebufferUsingCurrentContext(dstFbo, smallW, smallH)
        blurProgram.use()
        blurProgram.setSamplerTexIdUniform("uTex", srcTex, 0)
        blurProgram.setFloatsUniform("uStep", floatArrayOf(dx, dy))
        blurProgram.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    override fun release() {
        super.release()
        try {
            downProgram.delete(); blurProgram.delete(); compositeProgram.delete()
            if (fboA >= 0) GlUtil.deleteFbo(fboA)
            if (fboB >= 0) GlUtil.deleteFbo(fboB)
            if (texA >= 0) GlUtil.deleteTexture(texA)
            if (texB >= 0) GlUtil.deleteTexture(texB)
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
    }

    companion object {
        private const val BLUR_ITERATIONS = 3

        private const val VERTEX = """
attribute vec4 aFramePosition;
varying vec2 vTexCoord;
void main() {
  gl_Position = aFramePosition;
  vTexCoord = aFramePosition.xy * 0.5 + 0.5;
}
"""
        // Image coordinates are y-down (0 = top); GL texture coordinates are y-up.
        private const val FRAG_DOWNSAMPLE = """
precision highp float;
uniform sampler2D uTex;
uniform vec4 uSrc;
uniform vec2 uSpread;
varying vec2 vTexCoord;
void main() {
  vec2 p = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
  vec2 s = mix(uSrc.xy, uSrc.zw, p);
  vec2 t = vec2(s.x, 1.0 - s.y);
  vec4 c = texture2D(uTex, t + vec2(-uSpread.x, -uSpread.y))
         + texture2D(uTex, t + vec2( uSpread.x, -uSpread.y))
         + texture2D(uTex, t + vec2(-uSpread.x,  uSpread.y))
         + texture2D(uTex, t + vec2( uSpread.x,  uSpread.y));
  gl_FragColor = c * 0.25;
}
"""
        private const val FRAG_BLUR = """
precision highp float;
uniform sampler2D uTex;
uniform vec2 uStep;
varying vec2 vTexCoord;
void main() {
  vec4 c = texture2D(uTex, vTexCoord) * 0.227027;
  c += (texture2D(uTex, vTexCoord + uStep * 1.3846153) + texture2D(uTex, vTexCoord - uStep * 1.3846153)) * 0.3162162;
  c += (texture2D(uTex, vTexCoord + uStep * 3.2307692) + texture2D(uTex, vTexCoord - uStep * 3.2307692)) * 0.0702703;
  gl_FragColor = c;
}
"""
        private const val FRAG_COMPOSITE = """
precision highp float;
uniform sampler2D uTex;
uniform sampler2D uBlur;
uniform int uHasBg;
uniform float uBgDim;
uniform vec2 uOutSize;
uniform int uL0On;
uniform vec4 uL0Src;
uniform vec4 uL0Dst;
uniform float uL0Radius;
uniform int uL1On;
uniform vec4 uL1Src;
uniform vec4 uL1Dst;
uniform float uL1Radius;
varying vec2 vTexCoord;

float roundedMask(vec2 px, vec4 rectPx, float radius) {
  vec2 c = (rectPx.xy + rectPx.zw) * 0.5;
  vec2 halfSize = (rectPx.zw - rectPx.xy) * 0.5;
  float r = min(radius, min(halfSize.x, halfSize.y));
  vec2 d = abs(px - c) - (halfSize - r);
  float dist = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0) - r;
  return clamp(0.5 - dist, 0.0, 1.0);
}

vec3 drawLayer(vec3 base, vec2 p, vec2 px, vec4 src, vec4 dst, float radius) {
  vec2 uv = (p - dst.xy) / (dst.zw - dst.xy);
  float m = roundedMask(px, dst * vec4(uOutSize, uOutSize), radius);
  if (m <= 0.0) return base;
  vec2 s = mix(src.xy, src.zw, clamp(uv, 0.0, 1.0));
  vec3 c = texture2D(uTex, vec2(s.x, 1.0 - s.y)).rgb;
  return mix(base, c, m);
}

void main() {
  vec2 p = vec2(vTexCoord.x, 1.0 - vTexCoord.y);
  vec2 px = p * uOutSize;
  vec3 color = vec3(0.0);
  if (uHasBg == 1) color = texture2D(uBlur, vTexCoord).rgb * uBgDim;
  if (uL0On == 1) color = drawLayer(color, p, px, uL0Src, uL0Dst, uL0Radius);
  if (uL1On == 1) color = drawLayer(color, p, px, uL1Src, uL1Dst, uL1Radius);
  gl_FragColor = vec4(color, 1.0);
}
"""
    }
}
