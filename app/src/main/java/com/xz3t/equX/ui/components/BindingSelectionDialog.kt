package com.xz3t.equX.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xz3t.equX.R
import com.xz3t.equX.ui.theme.AppUiTheme

/** Пункт диалога привязки: [key] — id устройства/источника или пакет приложения. */
data class BindingOption(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val checked: Boolean,
)

/**
 * Диалог выбора привязок пресета (устройства вывода, источники звука, приложения) с галочками.
 * Нажатие на строку и на галочку — одно действие [onToggle]; проверку конфликтов делает вызывающий.
 */
@Composable
fun BindingSelectionDialog(
    title: String,
    options: List<BindingOption>,
    onToggle: (BindingOption) -> Unit,
    onDismiss: () -> Unit,
    emptyText: String? = null,
) {
    ScaledAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                if (options.isEmpty() && emptyText != null) {
                    Text(
                        text = emptyText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(option) }
                            .padding(vertical = 4.dp)
                    ) {
                        EquXCheckbox(checked = option.checked, onCheckedChange = { onToggle(option) })
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = option.title,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (option.checked) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurface
                            )
                            if (option.subtitle != null) {
                                Text(
                                    text = option.subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        }
    )
}
