package com.gameocr.app.ui

import androidx.compose.runtime.*
import com.gameocr.app.data.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/** Keep gesture state and callbacks out of the already-large SettingsScreen composable. */
@Stable
internal class GestureSettingsUiState {
    var globalEnabled by mutableStateOf(false)
    var bindings by mutableStateOf(GestureBindings.defaults)
    var edgeEnabled by mutableStateOf(false)
    var edgeRight by mutableStateOf(false)
    var edgeHeight by mutableIntStateOf(22)
    var twistEnabled by mutableStateOf(false)
    var twistSensitivity by mutableIntStateOf(3)
    var twistAction by mutableStateOf<GestureAction?>(null)
    var tiltEnabled by mutableStateOf(false)
    var tiltSensitivity by mutableIntStateOf(3)
    var tiltAction by mutableStateOf<GestureAction?>(null)

    fun load(settings: Settings) {
        globalEnabled = settings.gestureGlobalEnabled
        bindings = settings.gestureBindings
        edgeEnabled = settings.gestureEdgeEnabled
        edgeRight = settings.gestureEdgeRight
        edgeHeight = settings.gestureEdgeHeightPercent
        twistEnabled = settings.gestureTwistEnabled
        twistSensitivity = settings.gestureTwistSensitivity
        twistAction = settings.gestureTwistAction
        tiltEnabled = settings.gestureTiltEnabled
        tiltSensitivity = settings.gestureTiltSensitivity
        tiltAction = settings.gestureTiltAction
    }
}

@Composable
internal fun GestureSettingsSection(
    page: FloatingSettingsPage,
    scope: CoroutineScope,
    state: GestureSettingsUiState,
    viewModel: SettingsViewModel,
    onSaved: ((Settings) -> Settings) -> Unit,
) {
    GestureSettingsEditor(
        page = page,
        bindings = state.bindings,
        edgeEnabled = state.edgeEnabled, edgeRight = state.edgeRight, edgeHeight = state.edgeHeight,
        twistEnabled = state.twistEnabled, twistSensitivity = state.twistSensitivity, twistAction = state.twistAction,
        tiltEnabled = state.tiltEnabled, tiltSensitivity = state.tiltSensitivity, tiltAction = state.tiltAction,
        onMotionChange = { twist, enabled, sensitivity, action ->
            if (twist) {
                state.twistEnabled = enabled; state.twistSensitivity = sensitivity; state.twistAction = action
            } else {
                state.tiltEnabled = enabled; state.tiltSensitivity = sensitivity; state.tiltAction = action
            }
            scope.launch {
                viewModel.saveGestureMotion(twist, enabled, sensitivity, action)
                onSaved {
                    if (twist) it.copy(gestureTwistEnabled = enabled, gestureTwistSensitivity = sensitivity, gestureTwistAction = action)
                    else it.copy(gestureTiltEnabled = enabled, gestureTiltSensitivity = sensitivity, gestureTiltAction = action)
                }
            }
        },
        onEdgeChange = { enabled, right, height ->
            state.edgeEnabled = enabled; state.edgeRight = right; state.edgeHeight = height
            scope.launch {
                viewModel.saveGestureEdge(enabled, right, height)
                onSaved { it.copy(gestureEdgeEnabled = enabled, gestureEdgeRight = right, gestureEdgeHeightPercent = height) }
            }
        },
        onBindingChange = { gesture, action ->
            state.bindings = state.bindings + (gesture to action)
            scope.launch {
                viewModel.saveGestureBinding(gesture, action)
                onSaved { it.copy(gestureBindings = state.bindings) }
            }
        },
    )
}
