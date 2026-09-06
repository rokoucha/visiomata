package net.rokoucha.visiomata.playback.mpeg2toh264

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

@UnstableApi
internal class LinearDeinterlaceEffect(
    private val metadataQueue: DeinterlaceMetadataQueue,
) : GlEffect {
    override fun toGlShaderProgram(
        context: Context,
        useHdr: Boolean,
    ): GlShaderProgram = LinearDeinterlaceShaderProgram(metadataQueue, useHdr)

    override fun isNoOp(
        inputWidth: Int,
        inputHeight: Int,
    ): Boolean = false
}

@UnstableApi
private class LinearDeinterlaceShaderProgram(
    private val metadataQueue: DeinterlaceMetadataQueue,
    useHdr: Boolean,
) : BaseGlShaderProgram(useHdr, 1) {
    private val glProgram =
        try {
            GlProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
    private var inputHeight = 1

    init {
        glProgram.setBufferAttribute(
            "aFramePosition",
            GlUtil.getNormalizedCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
    }

    override fun configure(
        inputWidth: Int,
        inputHeight: Int,
    ): Size {
        this.inputHeight = inputHeight.coerceAtLeast(1)
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(
        inputTexId: Int,
        presentationTimeUs: Long,
    ) {
        val info =
            metadataQueue.take(presentationTimeUs)
                ?: DeinterlaceFrameInfo.InterlacedTopFieldFirst
        try {
            glProgram.use()
            glProgram.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
            glProgram.setFloatUniform("uHeight", inputHeight.toFloat())
            glProgram.setIntUniform("uInterlaced", if (info.interlaced) 1 else 0)
            glProgram.setIntUniform("uTopField", if (info.topFieldFirst) 1 else 0)
            glProgram.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error, presentationTimeUs)
        }
    }

    override fun release() {
        super.release()
        try {
            glProgram.delete()
        } catch (error: GlUtil.GlException) {
            throw VideoFrameProcessingException(error)
        }
    }

    private companion object {
        const val VERTEX_SHADER = """
      attribute vec4 aFramePosition;
      varying vec2 vTexCoord;
      void main() {
        gl_Position = aFramePosition;
        vTexCoord = (aFramePosition.xy + 1.0) * 0.5;
      }
    """

        const val FRAGMENT_SHADER = """
      precision highp float;
      uniform sampler2D uTexSampler;
      uniform float uHeight;
      uniform int uInterlaced;
      uniform int uTopField;
      varying vec2 vTexCoord;

      void main() {
        if (uInterlaced == 0) {
          gl_FragColor = texture2D(uTexSampler, vTexCoord);
          return;
        }

        float topLine = floor((1.0 - vTexCoord.y) * uHeight);
        float wantedParity = uTopField == 1 ? 0.0 : 1.0;
        float lineParity = mod(topLine, 2.0);
        if (lineParity == wantedParity) {
          gl_FragColor = texture2D(uTexSampler, vTexCoord);
          return;
        }

        float texelY = 1.0 / uHeight;
        vec2 above = vec2(vTexCoord.x, min(1.0, vTexCoord.y + texelY));
        vec2 below = vec2(vTexCoord.x, max(0.0, vTexCoord.y - texelY));
        gl_FragColor = 0.5 * (texture2D(uTexSampler, above) + texture2D(uTexSampler, below));
      }
    """
    }
}
