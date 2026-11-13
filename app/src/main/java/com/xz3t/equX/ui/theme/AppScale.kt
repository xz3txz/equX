package com.xz3t.equX.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.xz3t.equX.data.APP_SCALE_DEFAULT

/**
 * Текущий пользовательский масштаб интерфейса (0.5x..2.0x).
 *
 * В отличие от [LocalDensity], этот CompositionLocal проваливается внутрь
 * Compose-диалогов (AlertDialog / Dialog), которые живут в отдельном окне и не
 * наследуют переопределённый [LocalDensity] родителя.
 */
val LocalAppScale = staticCompositionLocalOf { APP_SCALE_DEFAULT }

/**
 * Переопределяет [LocalDensity] на основе [LocalAppScale].
 *
 * Главный экран масштабируется родительским CompositionLocalProvider в MainActivity,
 * а диалоги/оверлеи должны оборачиваться этим провайдером внутри своего окна,
 * потому что их собственный LocalDensity задаётся отдельной ComposeView.
 */
@Composable
fun ProvideScaledDensity(content: @Composable () -> Unit) {
    val scale = LocalAppScale.current
    val base = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(
            density = base.density * scale,
            fontScale = base.fontScale,
        ),
        content = content,
    )
}
