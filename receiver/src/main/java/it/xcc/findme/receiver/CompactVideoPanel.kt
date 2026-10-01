/**
 * @author Infinity
 * @description Anteprima video a tutta larghezza con colonna laterale dei comandi.
 * @modified 01.10.2026 - Infinity | Ripristinato il riquadro ampio e compattate le icone.
 * @modified 01.10.2026 - Infinity | Prima implementazione.
 */
package it.xcc.findme.receiver

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.FiberManualRecord
import androidx.compose.material.icons.outlined.Fullscreen
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.xcc.findme.receiver.recording.LocalRecordingState
import it.xcc.findme.receiver.recording.RecordingPolicy

@Composable
internal fun CompactVideoPanel(
    streaming: Boolean,
    trackAvailable: Boolean,
    cameraInterrupted: Boolean,
    cameraSwitchPending: Boolean,
    recordingState: LocalRecordingState,
    recordingEnabled: Boolean,
    onCameraSwitch: () -> Unit,
    onTakePhoto: () -> Unit,
    onRecordingToggle: () -> Unit,
    onFullscreen: () -> Unit,
    videoContent: @Composable BoxScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Black),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (streaming) {
                videoContent()
            }
            if (cameraInterrupted) {
                Text(
                    "La fotocamera locale ha interrotto lo streaming.\n" +
                        "Spegnilo e riaccendilo per riprendere.",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.78f))
                        .padding(8.dp),
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (!streaming) {
                Text(
                    "Video non attivo",
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            VideoRecordingTimer(
                state = recordingState,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(52.dp)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconButton(
                    onClick = onFullscreen,
                    enabled = streaming && trackAvailable,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Outlined.Fullscreen,
                        contentDescription = "Video a schermo intero",
                        modifier = Modifier.size(22.dp),
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = onCameraSwitch,
                    enabled = streaming && !recordingState.isActive && !cameraSwitchPending,
                    modifier = Modifier.size(44.dp),
                ) {
                    if (cameraSwitchPending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 3.dp,
                        )
                    } else {
                        Icon(
                            Icons.Outlined.Cameraswitch,
                            contentDescription = "Cambia fotocamera",
                            modifier = Modifier.size(22.dp),
                            tint = Color.White,
                        )
                    }
                }
                IconButton(
                    onClick = onTakePhoto,
                    enabled = streaming && trackAvailable,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        Icons.Outlined.CameraAlt,
                        contentDescription = "Scatta foto",
                        modifier = Modifier.size(22.dp),
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = onRecordingToggle,
                    enabled = recordingEnabled || recordingState.isActive,
                    modifier = Modifier.size(44.dp),
                ) {
                    when (recordingState) {
                        LocalRecordingState.Starting,
                        LocalRecordingState.Finalizing,
                        -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                        )
                        is LocalRecordingState.Recording -> Icon(
                            Icons.Outlined.StopCircle,
                            contentDescription = "Ferma registrazione",
                            modifier = Modifier.size(22.dp),
                            tint = Color.Red,
                        )
                        LocalRecordingState.Idle -> Icon(
                            Icons.Outlined.FiberManualRecord,
                            contentDescription = "Registra",
                            modifier = Modifier.size(22.dp),
                            tint = Color.Red,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun VideoRecordingTimer(
    state: LocalRecordingState,
    modifier: Modifier = Modifier,
) {
    (state as? LocalRecordingState.Recording)?.let {
        Text(
            "REC ${RecordingPolicy.formatElapsed(it.elapsedMs)}",
            modifier = modifier
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            color = Color.Red,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
