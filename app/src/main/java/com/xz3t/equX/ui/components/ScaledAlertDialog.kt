package com.xz3t.equX.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xz3t.equX.ui.theme.ProvideScaledDensity

/**
 * AlertDialog, привязанный к пользовательскому масштабу интерфейса.
 *
 * Обычный AlertDialog рендерится в отдельном окне, где не действует переопределённый
 * на главном экране LocalDensity, поэтому содержимое здесь оборачивается в
 * [ProvideScaledDensity] внутри каждого слота.
 */
@Composable
fun ScaledAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { ProvideScaledDensity { confirmButton() } },
        modifier = modifier,
        dismissButton = dismissButton?.let { { ProvideScaledDensity { it() } } },
        title = title?.let { { ProvideScaledDensity { it() } } },
        text = text?.let { { ProvideScaledDensity { it() } } },
    )
}
