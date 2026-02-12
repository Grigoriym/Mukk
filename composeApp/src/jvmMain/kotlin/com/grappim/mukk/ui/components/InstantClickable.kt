@file:OptIn(ExperimentalComposeUiApi::class)

package com.grappim.mukk.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

fun Modifier.instantClickable(
    onClick: () -> Unit,
    onDoubleClick: () -> Unit
): Modifier = this.pointerInput(onClick, onDoubleClick) {
    var lastClickTime = 0L
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent()
            if (event.type == PointerEventType.Press &&
                event.button == PointerButton.Primary
            ) {
                val now = System.currentTimeMillis()
                if (now - lastClickTime < 300) {
                    onDoubleClick()
                    lastClickTime = 0L
                } else {
                    onClick()
                    lastClickTime = now
                }
            }
        }
    }
}
