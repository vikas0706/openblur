package io.github.vikas0706.openblur.export

import io.github.vikas0706.openblur.core.SourceVideoInfo

/**
 * The picture size the encoder is asked for and the rotation written next to it, predicted with
 * Transformer's rules: a tall picture is laid on its side unless portrait encoding is on.
 */
internal data class OutputGeometry(
    val encodedWidth: Int,
    val encodedHeight: Int,
    val rotationDegrees: Int,
    /** Value for Transformer.Builder.setPortraitEncodingEnabled. */
    val portraitEncoding: Boolean,
) {
    companion object {
        /**
         * Matches the source's coded size and rotation except for a portrait-coded source with a rotation
         * flag: sideways (90, 270) it comes out transposed, upside down (180) with rotation 0.
         */
        fun forSource(info: SourceVideoInfo, allowPortraitEncoding: Boolean = true): OutputGeometry {
            val displayWidth = info.displayWidth
            val displayHeight = info.displayHeight
            val codedPortrait = info.codedHeight > info.codedWidth && !info.isRotatedSideways
            if (codedPortrait && allowPortraitEncoding) {
                return OutputGeometry(displayWidth, displayHeight, rotationDegrees = 0, portraitEncoding = true)
            }
            if (displayWidth >= displayHeight) {
                val rotation = if (info.isRotatedSideways) 0 else normalize(info.rotationDegrees)
                return OutputGeometry(displayWidth, displayHeight, rotation, portraitEncoding = false)
            }
            val rotation = if (info.isRotatedSideways) normalize(info.rotationDegrees) else 90
            return OutputGeometry(displayHeight, displayWidth, rotation, portraitEncoding = false)
        }

        private fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
    }
}
