package com.xz3t.equX.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xz3t.equX.R
import com.xz3t.equX.data.FORBIDDEN_PRESET_NAME_CHARS
import com.xz3t.equX.data.PresetNameError

/**
 * Поле имени пресета / файла. Под полем всегда видна подсказка с запрещёнными символами;
 * она краснеет при нарушении, а для занятого имени заменяется на «Имя уже занято».
 * Пустое имя ошибкой не подсвечивается — просто неактивна кнопка подтверждения.
 */
@Composable
fun PresetNameField(
    value: String,
    onValueChange: (String) -> Unit,
    error: PresetNameError?,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
) {
    val isError = error != null && error != PresetNameError.EMPTY
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        singleLine = true,
        isError = isError,
        label = label,
        placeholder = placeholder,
        supportingText = {
            val text = if (error == PresetNameError.TAKEN) {
                stringResource(R.string.name_taken_error)
            } else {
                stringResource(R.string.name_rules_hint, FORBIDDEN_PRESET_NAME_CHARS.toList().joinToString(" "))
            }
            Text(
                text = text,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    )
}
