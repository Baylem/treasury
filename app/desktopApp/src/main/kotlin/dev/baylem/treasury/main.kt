package dev.baylem.treasury

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Treasury",
    ) {
        App()
    }
}