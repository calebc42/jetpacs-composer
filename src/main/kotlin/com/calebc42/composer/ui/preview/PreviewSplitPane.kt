// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.ui.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.awt.Cursor
import kotlin.math.roundToInt

/** Which side is visible when the window is too narrow for a useful split. */
enum class CompactPreviewPane { Editor, Preview }

/**
 * Ephemeral layout state for [PreviewSplitPane]. Keep an instance in
 * `remember(session)` when each editor session should retain its own layout.
 */
@Stable
class PreviewSplitPaneState(
    initialPreviewWidth: Dp = DefaultPreviewWidth,
    initialCompactPane: CompactPreviewPane = CompactPreviewPane.Editor,
) {
    var previewWidth by mutableStateOf(initialPreviewWidth)
        private set

    var compactPane by mutableStateOf(initialCompactPane)
        private set

    fun updatePreviewWidth(width: Dp) {
        previewWidth = width
    }

    fun showCompactPane(pane: CompactPreviewPane) {
        compactPane = pane
    }
}

@Composable
fun rememberPreviewSplitPaneState(
    initialPreviewWidth: Dp = DefaultPreviewWidth,
    initialCompactPane: CompactPreviewPane = CompactPreviewPane.Editor,
): PreviewSplitPaneState = remember(initialPreviewWidth, initialCompactPane) {
    PreviewSplitPaneState(initialPreviewWidth, initialCompactPane)
}

/**
 * Responsive editor/preview container.
 *
 * A wide window gets a bounded, resizable preview on the right. When both
 * panes cannot retain useful minimum widths, an Editor/Preview switch replaces
 * the split instead of squeezing either pane.
 */
@Composable
fun PreviewSplitPane(
    previewVisible: Boolean,
    modifier: Modifier = Modifier,
    state: PreviewSplitPaneState = rememberPreviewSplitPaneState(),
    minPreviewWidth: Dp = MinPreviewWidth,
    maxPreviewWidth: Dp = MaxPreviewWidth,
    minEditorWidth: Dp = 480.dp,
    editorLabel: String = "Editor",
    previewLabel: String = "Preview",
    editorContent: @Composable BoxScope.() -> Unit,
    previewContent: @Composable BoxScope.() -> Unit,
) {
    require(minPreviewWidth > 0.dp) { "minPreviewWidth must be positive" }
    require(maxPreviewWidth >= minPreviewWidth) {
        "maxPreviewWidth must be at least minPreviewWidth"
    }
    require(minEditorWidth > 0.dp) { "minEditorWidth must be positive" }

    BoxWithConstraints(modifier) {
        if (!previewVisible) {
            Box(Modifier.fillMaxSize(), content = editorContent)
            return@BoxWithConstraints
        }

        val canSplit = maxWidth >= minEditorWidth + minPreviewWidth + ResizeHandleWidth
        if (!canSplit) {
            CompactPaneLayout(
                selectedPane = state.compactPane,
                onSelectPane = state::showCompactPane,
                editorLabel = editorLabel,
                previewLabel = previewLabel,
                editorContent = editorContent,
                previewContent = previewContent,
            )
            return@BoxWithConstraints
        }

        val effectiveMaxPreviewWidth = minOf(
            maxPreviewWidth,
            maxWidth - minEditorWidth - ResizeHandleWidth,
        )
        val renderedPreviewWidth = state.previewWidth.coerceIn(
            minPreviewWidth,
            effectiveMaxPreviewWidth,
        )

        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxHeight(), content = editorContent)
            PreviewResizeHandle(
                previewWidth = renderedPreviewWidth,
                minPreviewWidth = minPreviewWidth,
                maxPreviewWidth = effectiveMaxPreviewWidth,
                onPreviewWidthChange = state::updatePreviewWidth,
            )
            Box(
                Modifier.width(renderedPreviewWidth).fillMaxHeight(),
                content = previewContent,
            )
        }
    }
}

@Composable
private fun CompactPaneLayout(
    selectedPane: CompactPreviewPane,
    onSelectPane: (CompactPreviewPane) -> Unit,
    editorLabel: String,
    previewLabel: String,
    editorContent: @Composable BoxScope.() -> Unit,
    previewContent: @Composable BoxScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 1.dp) {
            Row(
                Modifier.fillMaxWidth().selectableGroup().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompactPaneChoice(
                    label = editorLabel,
                    selected = selectedPane == CompactPreviewPane.Editor,
                    onClick = { onSelectPane(CompactPreviewPane.Editor) },
                    modifier = Modifier.weight(1f),
                )
                CompactPaneChoice(
                    label = previewLabel,
                    selected = selectedPane == CompactPreviewPane.Preview,
                    onClick = { onSelectPane(CompactPreviewPane.Preview) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (selectedPane) {
                CompactPreviewPane.Editor -> editorContent()
                CompactPreviewPane.Preview -> previewContent()
            }
        }
    }
}

@Composable
private fun CompactPaneChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.selectable(
            selected = selected,
            onClick = onClick,
            role = Role.Tab,
        ),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PreviewResizeHandle(
    previewWidth: Dp,
    minPreviewWidth: Dp,
    maxPreviewWidth: Dp,
    onPreviewWidthChange: (Dp) -> Unit,
) {
    val density = LocalDensity.current

    fun resizeBy(delta: Dp): Boolean {
        val resized = (previewWidth + delta).coerceIn(minPreviewWidth, maxPreviewWidth)
        if (resized == previewWidth) return false
        onPreviewWidthChange(resized)
        return true
    }

    Box(
        Modifier
            .width(ResizeHandleWidth)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .semantics {
                contentDescription = "Resize preview pane"
                stateDescription = "Preview width ${previewWidth.value.roundToInt()} dp"
                role = Role.Button
                customActions = listOf(
                    CustomAccessibilityAction("Increase preview width") {
                        resizeBy(KeyboardResizeStep)
                    },
                    CustomAccessibilityAction("Decrease preview width") {
                        resizeBy(-KeyboardResizeStep)
                    },
                )
            }
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> resizeBy(KeyboardResizeStep)
                    Key.DirectionRight -> resizeBy(-KeyboardResizeStep)
                    else -> false
                }
            }
            .focusable()
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(previewWidth, minPreviewWidth, maxPreviewWidth) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val delta = with(density) { (-dragAmount.x).toDp() }
                    resizeBy(delta)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        VerticalDivider(
            modifier = Modifier.fillMaxHeight().width(2.dp),
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

val MinPreviewWidth = 320.dp
val DefaultPreviewWidth = 380.dp
val MaxPreviewWidth = 520.dp

private val ResizeHandleWidth = 8.dp
private val KeyboardResizeStep = 20.dp
