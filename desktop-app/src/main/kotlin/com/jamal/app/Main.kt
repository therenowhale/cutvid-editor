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
import javax.swing.SwingUtilities

private val videoExtensions = setOf("mov", "mp4", "m4v", "avi", "mkv", "webm")

private data class VideoFile(val file: File) {
    val label: String get() = file.name
}

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
                    onStatus = { message -> feedback = message },
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
                VideoInputCard(
                    title = "Background video",
                    description = "The video behind the outlined cutout.",
                    selected = backgroundVideo,
                    onChoose = onChooseBackground,
                )

                if (feedback != null) {
                    Text(feedback, color = Color(0xFF9CC8FF), fontSize = 14.sp)
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onPrepareRender,
                    enabled = referenceVideo != null && backgroundVideo != null,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3D7EFF),
                        disabledContainerColor = Color(0xFF2A2C31),
                    ),
                ) {
                    Text("Prepare render", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
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

private fun File.isSupportedVideo(): Boolean = extension.lowercase() in videoExtensions

private fun launchRenderEngine(reference: File, background: File, onStatus: (String) -> Unit) {
    val engine = resolveEngineExecutable()
    if (engine == null) {
        onStatus("C++ engine was not found. Build render-engine, then restart the app.")
        return
    }

    val jobFile = writeRenderJob(reference, background)
    onStatus("Starting C++ engine…")
    Thread {
        try {
            val process = ProcessBuilder(engine.absolutePath, jobFile.toString())
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { event ->
                    SwingUtilities.invokeLater { onStatus(engineMessage(event)) }
                }
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                SwingUtilities.invokeLater { onStatus("C++ engine stopped with code $exitCode.") }
            }
        } catch (error: Exception) {
            SwingUtilities.invokeLater { onStatus("Could not start C++ engine: ${error.message}") }
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
        var directory: File? = launchDirectory
        repeat(4) {
            directory?.let { add(File(it, "render-engine/build/jamal-render-engine")) }
            directory = directory?.parentFile
        }
    }
    return candidates.firstOrNull(File::canExecute)
}

private fun writeRenderJob(reference: File, background: File): Path {
    val jobsDirectory = Path.of(System.getProperty("user.home"), ".jamal", "jobs")
    Files.createDirectories(jobsDirectory)
    val jobFile = Files.createTempFile(jobsDirectory, "render-", ".render-job.json")
    val json = """
        {
          "version": 1,
          "referenceVideo": "${reference.absolutePath.toJsonString()}",
          "backgroundVideo": "${background.absolutePath.toJsonString()}"
        }
    """.trimIndent()
    Files.writeString(jobFile, json, StandardCharsets.UTF_8)
    return jobFile
}

private fun String.toJsonString(): String =
    replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

private fun engineMessage(event: String): String {
    val message = Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]*)").find(event)?.groupValues?.get(1)
    return message ?: event
}
