package com.jqc.webplayer.player

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride

data class QualityOption(
    val label: String,
    val height: Int?,
    val group: TrackGroup?,
    val trackIndex: Int,
)

object TrackQualityController {
    fun qualities(player: Player): List<QualityOption> {
        val result = mutableListOf<QualityOption>()
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (index in 0 until group.length) {
                val format = group.getTrackFormat(index)
                val height = format.height.takeIf { it > 0 }
                if (height != null) {
                    result += QualityOption(
                        label = "${height}P",
                        height = height,
                        group = group.mediaTrackGroup,
                        trackIndex = index,
                    )
                }
            }
        }
        return result.distinctBy { it.label }.sortedByDescending { it.height ?: 0 }
    }

    fun selectedHeight(player: Player): Int? {
        val tracks = player.currentTracks
        for (group in tracks.groups) {
            if (group.type != C.TRACK_TYPE_VIDEO) continue
            for (index in 0 until group.length) {
                if (group.isTrackSelected(index)) {
                    val height = group.getTrackFormat(index).height
                    if (height > 0) return height
                }
            }
        }
        return null
    }

    fun select(player: Player, option: QualityOption) {
        val group = option.group ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group, option.trackIndex))
            .build()
    }

    fun clearOverride(player: Player) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
    }
}
