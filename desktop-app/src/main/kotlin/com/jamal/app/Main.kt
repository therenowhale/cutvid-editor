package com.jamal.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Window as AwtWindow
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import javax.swing.SwingUtilities

private val videoExtensions = setOf("mov", "mp4", "m4v", "avi", "mkv", "webm")

private data class VideoFile(val file: File) {
    val label: String get() = file.name
}

private data class RenderSettings(
    val outlinePixels: String = "12",
    val scalePercent: String = "46",
    val horizontalPercent: String = "2",
    val bottomPercent: String = "0",
    val outlineColor: String = "#FFFFFF",
    val exportDirectory: File? = null,
)

private data class RenderProgress(
    val message: String,
    val percent: Int? = null,
    val isRunning: Boolean = false,
)

fun main() = application {
    Window(
        title = "Jamal — Video Cutout",
        onCloseRequest = ::exitApplication,
        state = WindowState(width = 940.dp, height = 760.dp),
        resizable = false,
    ) {
        var referenceVideo by remember { mutableStateOf<VideoFile?>(null) }
        var backgroundVideo by remember { mutableStateOf<VideoFile?>(null) }
        var feedback by remember { mutableStateOf<String?>(null) }
        var renderProgress by remember { mutableStateOf<RenderProgress?>(null) }
        var settings by remember { mutableStateOf(RenderSettings()) }

        WindowDropHandler(
            window = window,
            onFilesDropped = { droppedFiles ->
            var nextReference = referenceVideo
            var nextBackground = backgroundVideo
            droppedFiles.forEach { droppedFile ->
                when {
                    !droppedFile.isSupportedVideo() -> feedback = "Choose a video file (MP4, MOV, M4V, AVI, MKV, or WebM)."
                    nextReference == null -> nextReference = VideoFile(droppedFile)
                    nextBackground == null -> nextBackground = VideoFile(droppedFile)
                    else -> feedback = "Both inputs are already selected. Use Change to replace a file."
                }
            }
            referenceVideo = nextReference
            backgroundVideo = nextBackground
            if (droppedFiles.any { it.isSupportedVideo() }) feedback = "Video input updated."
            },
            onDropError = { message -> feedback = message },
        )

        App(
            referenceVideo = referenceVideo,
            backgroundVideo = backgroundVideo,
            feedback = feedback,
            renderProgress = renderProgress,
            settings = settings,
            onSettingsChange = { settings = it },
            onChooseReference = {
                chooseVideo(window)?.let { file ->
                    if (file.isSupportedVideo()) referenceVideo = VideoFile(file)
                    else feedback = "That file is not a supported video."
                }
            },
            onChooseBackground = {
                chooseVideo(window)?.let { file ->
                    if (file.isSupportedVideo()) backgroundVideo = VideoFile(file)
                    else feedback = "That file is not a supported video."
                }
            },
            onPrepareRender = {
                launchRenderEngine(
                    reference = requireNotNull(referenceVideo).file,
                    background = requireNotNull(backgroundVideo).file,
                    settings = settings,
                    onStatus = { progress -> renderProgress = progress },
                )
            },
        )
    }
}

@Composable
private fun App(
    referenceVideo: VideoFile?,
    backgroundVideo: VideoFile?,
    feedback: String?,
    renderProgress: RenderProgress?,
    settings: RenderSettings,
    onSettingsChange: (RenderSettings) -> Unit,
    onChooseReference: () -> Unit,
    onChooseBackground: () -> Unit,
    onPrepareRender: () -> Unit,
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101114)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 52.dp, vertical = 42.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text("Jamal", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Select the two videos for your cutout composition.",
                    color = Color(0xFFB9BBC3),
                    fontSize = 16.sp,
                )
                Text(
                    "You can drag one or two video files anywhere into this window. They fill Reference first, then Background.",
                    color = Color(0xFF838793),
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(8.dp))

                VideoInputCard(
                    title = "Reference video",
                    description = "The video containing the person to cut out.",
                    selected = referenceVideo,
                    onChoose = onChooseReference,
                )
                RenderSettingsCard(settings, onSettingsChange)
                VideoInputCard(
                    title = "Background video",
                    description = "The video behind the outlined cutout.",
                    selected = backgroundVideo,
                    onChoose = onChooseBackground,
                )

                if (feedback != null) {
                    Text(feedback, color = Color(0xFF9CC8FF), fontSize = 14.sp)
                }
                if (renderProgress != null) {
                    RenderProgressCard(renderProgress)
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onPrepareRender,
                    enabled = referenceVideo != null && backgroundVideo != null && renderProgress?.isRunning != true,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3D7EFF),
                        disabledContainerColor = Color(0xFF2A2C31),
                    ),
                ) {
                    Text(if (renderProgress?.isRunning == true) "Rendering…" else "Prepare render", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun RenderProgressCard(progress: RenderProgress) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Обработка", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(progress.percent?.let { "$it%" } ?: "…", color = Color(0xFF9CC8FF), fontWeight = FontWeight.Bold)
        }
        LinearProgressIndicator(
            progress = { (progress.percent ?: 0).coerceIn(0, 100) / 100f },
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFF3D7EFF),
            trackColor = Color(0xFF2A2C31),
        )
        Text(progress.message, color = Color(0xFF9CC8FF), fontSize = 14.sp)
    }
}

@Composable
private fun RenderSettingsCard(settings: RenderSettings, onChange: (RenderSettings) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF1A1C21), shape)
            .border(1.dp, Color(0xFF343842), shape).padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Composition", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text("Position and scale are percentages of the output frame. The reference video's audio is retained.", color = Color(0xFF9A9EAA), fontSize = 12.sp)
        SettingsField("Outline (pixels)", settings.outlinePixels) { onChange(settings.copy(outlinePixels = it)) }
        SettingsField("Person scale (% of frame height)", settings.scalePercent) { onChange(settings.copy(scalePercent = it)) }
        SettingsField("Left margin (% of frame width)", settings.horizontalPercent) { onChange(settings.copy(horizontalPercent = it)) }
        SettingsField("Bottom margin (% of frame height)", settings.bottomPercent) { onChange(settings.copy(bottomPercent = it)) }
        SettingsField("Outline colour (#RRGGBB)", settings.outlineColor) { onChange(settings.copy(outlineColor = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Export folder: ${settings.exportDirectory?.absolutePath ?: "~/.jamal/exports"}", modifier = Modifier.weight(1f), color = Color(0xFFE8EAF0), maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
            Spacer(Modifier.width(12.dp))
            Button(onClick = { chooseDirectory()?.let { onChange(settings.copy(exportDirectory = it)) } }) { Text("Choose folder") }
        }
    }
}

@Composable
private fun SettingsField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
}

@Composable
private fun VideoInputCard(
    title: String,
    description: String,
    selected: VideoFile?,
    onChoose: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1C21), shape)
            .border(1.dp, Color(0xFF343842), shape)
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(description, color = Color(0xFF9A9EAA), fontSize = 14.sp)
        Text("Drop a video anywhere on the window, or choose it below.", color = Color(0xFF6E7480), fontSize = 12.sp)
        Spacer(Modifier.height(7.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                Text(
                    text = selected?.label ?: "No video selected",
                    color = if (selected == null) Color(0xFF777C87) else Color(0xFFE8EAF0),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(16.dp))
            Button(onClick = onChoose) {
                Text(if (selected == null) "Choose file" else "Change")
            }
        }
    }
}

@Composable
private fun WindowDropHandler(
    window: AwtWindow,
    onFilesDropped: (List<File>) -> Unit,
    onDropError: (String) -> Unit,
) {
    val currentHandler by rememberUpdatedState(onFilesDropped)
    val currentErrorHandler by rememberUpdatedState(onDropError)

    DisposableEffect(window) {
        val target = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun drop(event: DropTargetDropEvent) {
                try {
                    if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                        event.rejectDrop()
                        currentErrorHandler("Drop video files from Finder or use Choose file.")
                        return
                    }
                    event.acceptDrop(DnDConstants.ACTION_COPY)
                    val files = event.transferable
                        .getTransferData(DataFlavor.javaFileListFlavor) as? List<*>
                    val videoFiles = files?.filterIsInstance<File>().orEmpty()
                    if (videoFiles.isEmpty()) {
                        currentErrorHandler("No files were received. Try dragging the video file itself.")
                    } else {
                        currentHandler(videoFiles)
                    }
                    event.dropComplete(true)
                } catch (_: Exception) {
                    event.dropComplete(false)
                    currentErrorHandler("Could not read the dropped files. Try Choose file instead.")
                }
            }
        }, true)

        onDispose {
            window.dropTarget = null
        }
    }
}

private fun chooseVideo(parent: Frame): File? {
    val dialog = FileDialog(parent, "Choose a video", FileDialog.LOAD)
    dialog.isVisible = true
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(directory, name)
}

private fun chooseDirectory(): File? {
    val previous = System.getProperty("apple.awt.fileDialogForDirectories")
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    return try {
        val dialog = FileDialog(null as Frame?, "Choose export folder", FileDialog.LOAD)
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val name = dialog.file
        File(directory, name ?: "")
    } finally {
        if (previous == null) System.clearProperty("apple.awt.fileDialogForDirectories") else System.setProperty("apple.awt.fileDialogForDirectories", previous)
    }
}

private fun File.isSupportedVideo(): Boolean = extension.lowercase() in videoExtensions

private fun launchRenderEngine(reference: File, background: File, settings: RenderSettings, onStatus: (RenderProgress) -> Unit) {
    val engine = resolveEngineExecutable()
    if (engine == null) {
        onStatus(RenderProgress("C++ engine was not found. Build render-engine, then restart the app."))
        return
    }

    val jobFile = writeRenderJob(reference, background, settings)
    onStatus(RenderProgress("Starting C++ engine…", 0, true))
    Thread {
        try {
            val processBuilder = ProcessBuilder(engine.absolutePath, jobFile.toString())
                .redirectErrorStream(true)
            // ONNX Runtime and OpenBLAS both embed OpenMP on macOS. They are
            // isolated inside the application bundle, so allow their runtime
            // initialisation to coexist instead of aborting with exit code 134.
            processBuilder.environment()["KMP_DUPLICATE_LIB_OK"] = "TRUE"
            val process = processBuilder.start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { event ->
                    SwingUtilities.invokeLater { onStatus(engineProgress(event)) }
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                SwingUtilities.invokeLater { onStatus(RenderProgress("C++ engine stopped with code $exitCode.")) }
            }
        } catch (error: Exception) {
            SwingUtilities.invokeLater { onStatus(RenderProgress("Could not start C++ engine: ${error.message}")) }
        }
    }.apply {
        isDaemon = true
        start()
    }
}

private fun resolveEngineExecutable(): File? {
    val override = System.getProperty("jamal.engine.path")?.let(::File)
    val launchDirectory = File(System.getProperty("user.dir")).canonicalFile
    val candidates = buildList {
        if (override != null) add(override)
        System.getProperty("compose.application.resources.dir")?.let { resources ->
            extractPackagedRuntime(File(resources, "common/jamal-runtime.zip"))?.let(::add)
            extractPackagedRuntime(File(resources, "jamal-runtime.zip"))?.let(::add)
        }
        var directory: File? = launchDirectory
        repeat(4) {
            directory?.let { add(File(it, "render-engine/build/jamal-render-engine")) }
            directory = directory?.parentFile
        }
    }
    return candidates.firstOrNull { candidate -> candidate.setExecutable(true) && candidate.canExecute() }
}

private fun extractPackagedRuntime(archive: File): File? {
    if (!archive.isFile) return null
    val runtimeDirectory = File(System.getProperty("user.home"), ".jamal/runtime/1.0.3")
    val engine = File(runtimeDirectory, "jamal-render-engine")
    if (engine.isFile) return engine

    val temporaryDirectory = File(runtimeDirectory.parentFile, "runtime-extracting")
    temporaryDirectory.deleteRecursively()
    temporaryDirectory.mkdirs()
    ZipInputStream(archive.inputStream().buffered()).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val destination = File(temporaryDirectory, entry.name).canonicalFile
            if (!destination.path.startsWith("${temporaryDirectory.canonicalPath}${File.separator}")) {
                throw IllegalStateException("Invalid bundled runtime entry")
            }
            if (entry.isDirectory) destination.mkdirs() else {
                destination.parentFile.mkdirs()
                destination.outputStream().use { output -> zip.copyTo(output) }
            }
            zip.closeEntry()
        }
    }
    runtimeDirectory.deleteRecursively()
    if (!temporaryDirectory.renameTo(runtimeDirectory)) {
        throw IllegalStateException("Could not install bundled render runtime")
    }
    return engine
}

private fun writeRenderJob(reference: File, background: File, settings: RenderSettings): Path {
    val jobsDirectory = Path.of(System.getProperty("user.home"), ".jamal", "jobs")
    val exportsDirectory = (settings.exportDirectory?.toPath() ?: Path.of(System.getProperty("user.home"), ".jamal", "exports")).toAbsolutePath()
    Files.createDirectories(jobsDirectory)
    Files.createDirectories(exportsDirectory)
    val jobFile = Files.createTempFile(jobsDirectory, "render-", ".render-job.json")
    val output = exportsDirectory.resolve("jamal-${System.currentTimeMillis()}.mp4")
    val packagedResources = System.getProperty("compose.application.resources.dir")?.let(::File)
    val packagedModel = listOfNotNull(packagedResources?.resolve("common/modnet_photographic.onnx"), packagedResources?.resolve("modnet_photographic.onnx"))
        .firstOrNull(File::isFile)
    val developmentModel = File(System.getProperty("user.dir"), "models/modnet_photographic.onnx").takeIf(File::isFile)
    val model = packagedModel ?: developmentModel
    val json = """
        {
          "version": 1,
          "referenceVideo": "${reference.absolutePath.toJsonString()}",
          "backgroundVideo": "${background.absolutePath.toJsonString()}",
          "outputVideo": "${output.toString().toJsonString()}",
          "modelPath": "${model?.absolutePath?.toJsonString().orEmpty()}",
          "outlinePixels": "${settings.outlinePixels.toJsonString()}",
          "scalePercent": "${settings.scalePercent.toJsonString()}",
          "horizontalPercent": "${settings.horizontalPercent.toJsonString()}",
          "bottomPercent": "${settings.bottomPercent.toJsonString()}",
          "outlineColor": "${settings.outlineColor.toJsonString()}"
        }
    """.trimIndent()
    Files.writeString(jobFile, json, StandardCharsets.UTF_8)
    return jobFile
}

private fun String.toJsonString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun engineProgress(event: String): RenderProgress {
    val message = Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]*)").find(event)?.groupValues?.get(1)
    val percent = Regex("\\\"percent\\\"\\s*:\\s*(\\d+)").find(event)?.groupValues?.get(1)?.toIntOrNull()
    val type = Regex("\\\"type\\\"\\s*:\\s*\\\"([^\\\"]*)").find(event)?.groupValues?.get(1)
    return RenderProgress(message ?: event, percent, type == "progress")
}
