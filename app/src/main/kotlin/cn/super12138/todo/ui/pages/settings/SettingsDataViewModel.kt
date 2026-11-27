package cn.super12138.todo.ui.pages.settings

import cn.super12138.todo.logic.database.taskTags

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.super12138.todo.constants.Constants
import cn.super12138.todo.logic.TaskRepository
import cn.super12138.todo.utils.FileUtils
import com.jsoizo.kotlincsv.writer.CsvWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class SettingsDataViewModel(
    private val taskRepository: TaskRepository,
    private val csvWriter: CsvWriter,
    private val imports: cn.super12138.todo.logic.sync.TaskImports
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsDataUiState())
    val uiState = _uiState.asStateFlow()

    /**
     * 备份应用数据
     *
     * @param uri 备份文件路径的 URI
     * @param context 应用 Context
     * @param onResult 备份完成的回调函数
     */
    fun backupDataInZipFile(uri: Uri, context: Context, onResult: (completed: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { outputStream ->
                    imports.export(outputStream)
                }
            }.isSuccess
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    /**
     * 恢复应用数据
     *
     * @param uri 选择的恢复文件的 URI
     * @param context 应用 Context
     * @param onResult 恢复完成的回调函数
     */
    fun restoreAppData(uri: Uri, context: Context, onResult: (completed: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                requireNotNull(context.contentResolver.openInputStream(uri)).use { inputStream ->
                    imports.import(inputStream, context)
                }
            }.isSuccess
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    // 考虑重新设计API
    fun backupDataInCsvFile(uri: Uri, context: Context, onResult: (completed: Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val tasks = taskRepository.getAllTasks().first()

            val header =
                listOf("content", "category", "isCompleted", "priority", "dueDateMillis", "id", "createdAtMillis", "details", "tags", "dueTimeMinutes", "subtasks", "recurrence", "recurrenceParentId")

            val rows = tasks.map { task ->
                with(task) {
                    listOf(
                        content,
                        category,
                        isCompleted.toString(),
                        priority.toString(),
                        dueDateMillis?.toString() ?: "",
                        id.toString(),
                        createdAtMillis?.toString() ?: "",
                        details,
                        kotlinx.serialization.json.Json.encodeToString(task.taskTags),
                        dueTimeMinutes?.toString() ?: "",
                        kotlinx.serialization.json.Json.encodeToString(subtasks),
                        recurrence.orEmpty(),
                        recurrenceParentId?.toString().orEmpty()
                    )
                }
            }

            val result = runCatching {
                requireNotNull(context.contentResolver.openOutputStream(uri)).use { outputStream ->
                    outputStream.write(
                        csvWriter.writeAll(listOf(header) + rows)
                            .toByteArray(charset = Charsets.UTF_8)
                    )
                }
            }.isSuccess
            withContext(Dispatchers.Main) { onResult(result) }
        }
    }

    fun showRestoreDialog() = _uiState.update { it.copy(showRestoreDialog = true) }
    fun hideRestoreDialog() = _uiState.update { it.copy(showRestoreDialog = false) }
    // fun showBackupFormatDialog() = _uiState.update { it.copy(showBackupFormatDialog = true) }
    // fun hideBackupFormatDialog() = _uiState.update { it.copy(showBackupFormatDialog = false) }
}
