package com.xz3t.equX.ui.components

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xz3t.equX.R
import com.xz3t.equX.data.presetNameError
import com.xz3t.equX.ui.theme.AppUiTheme
import com.xz3t.equX.ui.theme.ProvideScaledDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

enum class FileManagerMode { IMPORT, EXPORT }

/** Порядок сортировки в файловом менеджере (имя / дата изменения, по возрастанию/убыванию). */
enum class FileSortOrder {
    NAME_ASC,
    NAME_DESC,
    MODIFIED_ASC,
    MODIFIED_DESC;

    fun labelRes(): Int = when (this) {
        NAME_ASC -> R.string.file_sort_name_asc
        NAME_DESC -> R.string.file_sort_name_desc
        MODIFIED_ASC -> R.string.file_sort_modified_asc
        MODIFIED_DESC -> R.string.file_sort_modified_desc
    }
}

/**
 * Минимальный встроенный файловый менеджер в теме equX.
 * Используется для экспорта/импорта JSON-пресетов на Android Automotive,
 * где системный DocumentsUI часто отсутствует.
 */
@Composable
fun FileManagerDialog(
    mode: FileManagerMode,
    initialFileName: String = "",
    onDismiss: () -> Unit,
    onFilePicked: (File) -> Unit = {},
    onExportConfirmed: (File) -> Unit = {},
) {
    val rootDir = remember { Environment.getExternalStorageDirectory() }
    var currentDir by remember { mutableStateOf(rootDir) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var fileName by remember(initialFileName) { mutableStateOf(initialFileName) }
    var sortOrder by remember { mutableStateOf(FileSortOrder.NAME_ASC) }
    var pendingOverwrite by remember { mutableStateOf<File?>(null) }

    LaunchedEffect(currentDir) {
        isLoading = true
        entries = withContext(Dispatchers.IO) { listDir(currentDir) }
        isLoading = false
    }

    val canGoUp = currentDir.parentFile != null && currentDir.path != rootDir.path

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        )
    ) {
        ProvideScaledDensity {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = AppUiTheme.appBackgroundColor()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .imePadding()
            ) {
                // Верхняя панель
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppUiTheme.topHeaderBackgroundColor())
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                if (mode == FileManagerMode.IMPORT) R.string.file_manager_import_title
                                else R.string.file_manager_export_title
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = currentDir.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { if (canGoUp) currentDir = currentDir.parentFile!! }, enabled = canGoUp) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.file_manager_up),
                            tint = if (canGoUp) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                        )
                    }
                }

                // Поле имени файла для экспорта
                if (mode == FileManagerMode.EXPORT) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Те же правила, что и для имени пресета (кроме уникальности): запрещённые
                        // символы и точка в конце ломают запись в общей памяти Android, FAT и Windows.
                        val baseName = fileName.trim().let {
                            if (it.endsWith(".json", ignoreCase = true)) it.dropLast(".json".length) else it
                        }
                        val fileNameError = presetNameError(baseName, emptyList())
                        PresetNameField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            error = fileNameError,
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.file_manager_filename)) },
                            placeholder = { Text(stringResource(R.string.file_manager_filename_hint)) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            enabled = fileNameError == null,
                            onClick = {
                                val trimmed = fileName.trim()
                                val finalName = if (trimmed.endsWith(".json", ignoreCase = true)) trimmed else "$trimmed.json"
                                val target = File(currentDir, finalName)
                                if (target.exists()) {
                                    pendingOverwrite = target
                                } else {
                                    onExportConfirmed(target)
                                }
                            }
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                }

                // Панель сортировки
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.file_sort_by),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            sortOrder = when (sortOrder) {
                                FileSortOrder.NAME_ASC -> FileSortOrder.NAME_DESC
                                FileSortOrder.NAME_DESC -> FileSortOrder.MODIFIED_ASC
                                FileSortOrder.MODIFIED_ASC -> FileSortOrder.MODIFIED_DESC
                                FileSortOrder.MODIFIED_DESC -> FileSortOrder.NAME_ASC
                            }
                        }
                    ) {
                        Text(
                            text = stringResource(sortOrder.labelRes()),
                            style = MaterialTheme.typography.bodyMedium,
                            color = AppUiTheme.accentColor(),
                        )
                    }
                }

                // Список папок и .json-файлов
                Box(modifier = Modifier.fillMaxSize()) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = AppUiTheme.accentColor()
                        )
                    } else if (entries.isEmpty()) {
                        Text(
                            text = stringResource(R.string.file_manager_empty),
                            modifier = Modifier.align(Alignment.Center),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(sortFiles(entries, sortOrder), key = { it.absolutePath }) { file ->
                                val isDir = file.isDirectory
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(AppUiTheme.activeHeaderColor())
                                        .clickable {
                                            if (isDir) {
                                                currentDir = file
                                            } else {
                                                onFilePicked(file)
                                            }
                                        }
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (isDir) Icons.Filled.Folder else Icons.AutoMirrored.Filled.InsertDriveFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp),
                                        tint = if (isDir) AppUiTheme.accentColor() else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = file.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    if (pendingOverwrite != null) {
        val target = pendingOverwrite!!
        ScaledAlertDialog(
            onDismissRequest = { pendingOverwrite = null },
            title = { Text(stringResource(R.string.file_manager_overwrite_title)) },
            text = { Text(stringResource(R.string.file_manager_overwrite_message, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingOverwrite = null
                    onExportConfirmed(target)
                }) {
                    Text(stringResource(R.string.overwrite))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingOverwrite = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

private fun listDir(dir: File): List<File> {
    return try {
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory || it.name.endsWith(".json", ignoreCase = true) }
            .toList()
    } catch (e: Exception) {
        emptyList()
    }
}

private fun sortFiles(files: List<File>, order: FileSortOrder): List<File> {
    // Папки всегда держим первыми (стандартное поведение файлового менеджера),
    // а внутри групп сортируем по выбранному критерию.
    return when (order) {
        FileSortOrder.NAME_ASC -> files.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )
        FileSortOrder.NAME_DESC -> files.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenByDescending { it.name.lowercase() }
        )
        FileSortOrder.MODIFIED_ASC -> files.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy { it.lastModified() }
        )
        FileSortOrder.MODIFIED_DESC -> files.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenByDescending { it.lastModified() }
        )
    }
}
