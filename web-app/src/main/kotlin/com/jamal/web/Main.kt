package com.jamal.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.Collections
import java.util.Base64
import java.util.Properties
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val PORT = 8787
private const val FREE_RENDER_LIMIT = 3
private val supportedExtensions = setOf("mov", "mp4", "m4v", "avi", "mkv", "webm")
private val jobs = ConcurrentHashMap<String, RenderJob>()
private val renderQueue = Executors.newSingleThreadExecutor { task ->
    Thread(task, "jamal-render-queue").apply { isDaemon = true }
}
private val deviceFingerprint by lazy(::localDeviceFingerprint)
private val serverStartedAt = System.currentTimeMillis()
private val dotEnvConfig by lazy(::loadDotEnv)

private data class UploadedFile(val name: String, val path: Path)
private data class RenderJob(
    val id: String,
    val reference: UploadedFile,
    val background: UploadedFile,
    val settings: RenderSettings,
    val output: Path,
    val queuedAt: Long = System.currentTimeMillis(),
    @Volatile var startedAt: Long? = null,
    @Volatile var finishedAt: Long? = null,
    @Volatile var message: String = "Waiting in render queue",
    @Volatile var percent: Int? = 0,
    @Volatile var state: JobState = JobState.QUEUED,
    @Volatile var lastLoggedPercent: Int = -1,
    @Volatile var lastLoggedMessage: String = "",
    @Volatile var lastEngineError: String? = null,
)

private enum class JobState { QUEUED, RENDERING, COMPLETE, FAILED }

private data class RenderSettings(
    val outlinePixels: String,
    val scalePercent: String,
    val horizontalPercent: String,
    val bottomPercent: String,
    val outlineColor: String,
)

fun main() {
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", PORT), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        val noisyPoll = path.matches(Regex("/api/renders/[^/]+")) || path in setOf("/api/admin/overview", "/api/admin/events")
        if (!noisyPoll) ActivityLog.debug(
            "http.request", "${exchange.requestMethod} $path",
            requestContext(exchange, mapOf(
                "userAgent" to exchange.requestHeaders.getFirst("User-Agent"),
                "contentLength" to exchange.requestHeaders.getFirst("Content-Length"),
            )),
        )
        try {
            when {
                exchange.requestMethod == "GET" && path == "/" -> respondHtml(exchange)
                exchange.requestMethod == "GET" && path == "/admin" -> respondHtml(exchange, ADMIN_PAGE)
                exchange.requestMethod == "GET" && path == "/api/quota" -> quotaStatus(exchange)
                exchange.requestMethod == "POST" && path == "/api/renders" -> createRenders(exchange)
                exchange.requestMethod == "GET" && path == "/api/admin/overview" -> adminOverview(exchange)
                exchange.requestMethod == "GET" && path == "/api/admin/events" -> adminEvents(exchange)
                exchange.requestMethod == "GET" && path == "/api/admin/logs/download" -> downloadActivityLog(exchange)
                exchange.requestMethod == "GET" && path.matches(Regex("/api/renders/[^/]+")) -> renderStatus(exchange)
                exchange.requestMethod == "GET" && path.matches(Regex("/api/renders/[^/]+/download")) -> downloadRender(exchange)
                else -> {
                    ActivityLog.warn("http.not_found", "${exchange.requestMethod} $path", requestContext(exchange))
                    respondText(exchange, 404, "Not found")
                }
            }
        } catch (error: Exception) {
            ActivityLog.error("http.unhandled_error", "Unhandled request error for ${exchange.requestMethod} $path", error, requestContext(exchange))
            try { respondJson(exchange, 500, "{\"error\":\"Internal server error. Check the admin activity log.\"}") } catch (_: Exception) { }
        }
    }
    server.executor = Executors.newCachedThreadPool()
    server.start()
    println("Jamal web app is ready at http://127.0.0.1:$PORT")
    println("Device ${deviceFingerprint.take(12)}… has ${UsageStore.used(deviceFingerprint)} of $FREE_RENDER_LIMIT free renders used.")
    println("Admin dashboard: http://127.0.0.1:$PORT/admin")
    dotEnvConfig.path?.let { println("Configuration loaded from $it") }
    ActivityLog.info(
        "server.started", "Jamal web app started",
        LogContext(device = deviceFingerprint.take(12), details = mapOf(
            "port" to PORT,
            "adminTokenConfigured" to !adminToken().isNullOrBlank(),
            "accessTokenConfigured" to !accessToken().isNullOrBlank(),
            "envFile" to dotEnvConfig.path?.toString(),
        )),
    )
    println("Press Ctrl+C to stop it.")
}

private fun quotaStatus(exchange: HttpExchange) {
    val used = UsageStore.used(deviceFingerprint)
    ActivityLog.info("quota.checked", "Render allowance checked", requestContext(exchange, mapOf("used" to used, "limit" to FREE_RENDER_LIMIT)))
    respondJson(exchange, 200, quotaJson(used))
}

private fun createRenders(exchange: HttpExchange) {
    try {
        val headerToken = decodeUtf8Base64Header(exchange, "X-Access-Token-Base64")
        val usedBeforeUpload = UsageStore.used(deviceFingerprint)
        if (usedBeforeUpload >= FREE_RENDER_LIMIT && !validAccessToken(headerToken.orEmpty())) {
            val message = if (headerToken.isNullOrBlank()) {
                "This device has no free renders left. Enter the access token before uploading."
            } else {
                "The access token is not valid."
            }
            ActivityLog.warn("quota.preflight_denied", message, requestContext(exchange, mapOf("tokenSupplied" to !headerToken.isNullOrBlank())))
            return respondJson(exchange, 403, "{\"error\":\"${message.jsonEscape()}\"}")
        }
        val fields = parseMultipart(exchange)
        val references = fields.files["reference"].orEmpty()
        val backgrounds = fields.files["background"].orEmpty()
        ActivityLog.info(
            "upload.received", "Video upload received",
            requestContext(exchange, mapOf(
                "referenceCount" to references.size,
                "backgroundCount" to backgrounds.size,
                "referenceFiles" to references.joinToString(" | ") { "${it.name} (${Files.size(it.path)} bytes)" },
                "backgroundFiles" to backgrounds.joinToString(" | ") { "${it.name} (${Files.size(it.path)} bytes)" },
            )),
        )
        require(references.isNotEmpty()) { "Choose at least one reference video." }
        require(backgrounds.isNotEmpty()) { "Choose at least one background video." }
        references.forEach { require(it.name.extension() in supportedExtensions) { "${it.name} is not a supported reference video." } }
        backgrounds.forEach { require(it.name.extension() in supportedExtensions) { "${it.name} is not a supported background video." } }

        val pairs = pairVideos(references, backgrounds)
        val suppliedToken = headerToken ?: fields.values["accessToken"].orEmpty()
        val unlocked = suppliedToken.isNotBlank() && validAccessToken(suppliedToken)
        if (!unlocked) {
            val reserved = UsageStore.reserveIfAvailable(deviceFingerprint, pairs.size, FREE_RENDER_LIMIT)
            if (!reserved) {
                val remaining = (FREE_RENDER_LIMIT - UsageStore.used(deviceFingerprint)).coerceAtLeast(0)
                val message = if (accessToken().isNullOrBlank()) {
                    "This device has $remaining free render${if (remaining == 1) "" else "s"} left. Set JAMAL_ACCESS_TOKEN on the server to enable an access token."
                } else if (suppliedToken.isNotBlank()) {
                    "The access token is not valid."
                } else {
                    "This batch needs ${pairs.size} render${if (pairs.size == 1) "" else "s"}, but this device has $remaining free. Enter the access token to continue."
                }
                ActivityLog.warn("quota.denied", message, requestContext(exchange, mapOf("requested" to pairs.size, "remaining" to remaining, "tokenSupplied" to suppliedToken.isNotBlank())))
                return respondJson(exchange, 403, "{\"error\":\"${message.jsonEscape()}\"}")
            }
        }

        val settings = RenderSettings(
            outlinePixels = fields.values["outlinePixels"].orDefault("12"),
            scalePercent = fields.values["scalePercent"].orDefault("46"),
            horizontalPercent = fields.values["horizontalPercent"].orDefault("2"),
            bottomPercent = fields.values["bottomPercent"].orDefault("0"),
            outlineColor = fields.values["outlineColor"].orDefault("#FFFFFF"),
        )
        val created = pairs.map { (reference, background) ->
            val jobId = UUID.randomUUID().toString()
            val output = exportsDirectory().resolve("jamal-${System.currentTimeMillis()}-${jobId.take(8)}.mp4")
            RenderJob(jobId, reference, background, settings, output).also { job ->
                jobs[jobId] = job
                ActivityLog.info(
                    "render.queued", "Render added to queue",
                    requestContext(exchange, mapOf(
                        "reference" to reference.name,
                        "background" to background.name,
                        "output" to output.fileName.toString(),
                        "unlocked" to unlocked,
                        "outlinePixels" to settings.outlinePixels,
                        "scalePercent" to settings.scalePercent,
                        "horizontalPercent" to settings.horizontalPercent,
                        "bottomPercent" to settings.bottomPercent,
                        "outlineColor" to settings.outlineColor,
                    )).copy(jobId = jobId),
                )
                renderQueue.submit { performRender(job) }
            }
        }
        val used = UsageStore.used(deviceFingerprint)
        ActivityLog.info("batch.accepted", "Render batch accepted", requestContext(exchange, mapOf("jobs" to created.size, "unlocked" to unlocked, "quotaUsed" to used)))
        val jobJson = created.joinToString(",") { "{\"id\":\"${it.id}\",\"reference\":\"${it.reference.name.jsonEscape()}\",\"background\":\"${it.background.name.jsonEscape()}\"}" }
        respondJson(exchange, 202, "{\"jobs\":[$jobJson],\"quota\":${quotaJson(used)}}")
    } catch (error: Exception) {
        ActivityLog.error("batch.rejected", error.message ?: "Invalid render request", error, requestContext(exchange))
        respondJson(exchange, 400, "{\"error\":\"${(error.message ?: "Invalid render request").jsonEscape()}\"}")
    }
}

private fun pairVideos(references: List<UploadedFile>, backgrounds: List<UploadedFile>): List<Pair<UploadedFile, UploadedFile>> = when {
    references.size == backgrounds.size -> references.zip(backgrounds)
    references.size == 1 -> backgrounds.map { references.first() to it }
    backgrounds.size == 1 -> references.map { it to backgrounds.first() }
    else -> error("Choose equal numbers of reference and background videos, or choose one video on either side to reuse it for every output.")
}

private fun renderStatus(exchange: HttpExchange) {
    val id = exchange.requestURI.path.substringAfterLast('/')
    val job = jobs[id] ?: return respondText(exchange, 404, "Unknown render")
    val queuePosition = if (job.state == JobState.QUEUED) {
        jobs.values.filter { it.state == JobState.QUEUED }
            .sortedWith(compareBy<RenderJob> { it.queuedAt }.thenBy { it.id })
            .indexOfFirst { it.id == job.id } + 1
    } else 0
    val now = job.finishedAt ?: System.currentTimeMillis()
    val elapsed = job.startedAt?.let { now - it } ?: 0
    val eta = job.percent?.takeIf { it in 1..99 }?.let { percent -> (elapsed * (100 - percent) / percent).coerceAtLeast(0) }
    respondJson(exchange, 200, """{"id":"${job.id}","reference":"${job.reference.name.jsonEscape()}","background":"${job.background.name.jsonEscape()}","message":"${job.message.jsonEscape()}","percent":${job.percent ?: "null"},"state":"${job.state.name.lowercase()}","queuePosition":$queuePosition,"queuedAt":${job.queuedAt},"startedAt":${job.startedAt ?: "null"},"elapsedMs":$elapsed,"etaMs":${eta ?: "null"},"running":${job.state == JobState.QUEUED || job.state == JobState.RENDERING},"failed":${job.state == JobState.FAILED},"ready":${Files.isRegularFile(job.output)}}""")
}

private fun downloadRender(exchange: HttpExchange) {
    val id = exchange.requestURI.path.removeSuffix("/download").substringAfterLast('/')
    val job = jobs[id] ?: run {
        ActivityLog.warn("download.unknown", "Download requested for unknown render", requestContext(exchange).copy(jobId = id))
        return respondText(exchange, 404, "Unknown render")
    }
    if (!Files.isRegularFile(job.output)) {
        ActivityLog.warn("download.not_ready", "Download requested before render was ready", requestContext(exchange).copy(jobId = id))
        return respondText(exchange, 409, "Render is not ready")
    }
    ActivityLog.info("download.started", "Completed video download started", requestContext(exchange, mapOf("bytes" to Files.size(job.output), "file" to job.output.fileName.toString())).copy(jobId = id))
    exchange.responseHeaders.add("Content-Type", "video/mp4")
    exchange.responseHeaders.add("Content-Disposition", "attachment; filename=jamal-${job.id.take(8)}.mp4")
    exchange.sendResponseHeaders(200, Files.size(job.output))
    Files.newInputStream(job.output).use { it.copyTo(exchange.responseBody) }
    exchange.close()
}

private fun performRender(job: RenderJob) {
    job.state = JobState.RENDERING
    job.startedAt = System.currentTimeMillis()
    job.message = "Starting render engine"
    ActivityLog.info("render.started", "Render engine starting", jobContext(job))
    try {
        val engine = resolveEngineExecutable() ?: error("Render engine was not found. Build render-engine first.")
        val jobFile = writeRenderJob(job)
        ProcessBuilder(engine.absolutePath, jobFile.toString()).apply {
            redirectErrorStream(true)
            environment()["KMP_DUPLICATE_LIB_OK"] = "TRUE"
        }.start().also { process ->
            process.inputStream.bufferedReader().useLines { lines -> lines.forEach { event ->
                ActivityLog.debug("engine.output", event.take(8_000), jobContext(job))
                updateProgress(job, event)
            } }
            val exit = process.waitFor()
            if (exit != 0) error(job.lastEngineError ?: "Render engine stopped with code $exit.")
        }
        job.message = "Render complete"
        job.percent = 100
        job.state = JobState.COMPLETE
        ActivityLog.info("render.completed", "Render completed", jobContext(job, mapOf(
            "durationMs" to ((System.currentTimeMillis()) - (job.startedAt ?: System.currentTimeMillis())),
            "outputBytes" to Files.size(job.output),
            "output" to job.output.toString(),
        )))
    } catch (error: Exception) {
        job.message = error.message ?: "Render failed"
        job.state = JobState.FAILED
        ActivityLog.error("render.failed", job.message, error, jobContext(job, mapOf(
            "durationMs" to (System.currentTimeMillis() - (job.startedAt ?: System.currentTimeMillis())),
        )))
    } finally {
        job.finishedAt = System.currentTimeMillis()
    }
}

private fun updateProgress(job: RenderJob, event: String) {
    val type = Regex("\\\"type\\\"\\s*:\\s*\\\"([^\\\"]*)").find(event)?.groupValues?.get(1)
    val message = Regex("\\\"message\\\"\\s*:\\s*\\\"([^\\\"]*)").find(event)?.groupValues?.get(1)
    if (type == "error") {
        job.lastEngineError = message ?: "Render engine failed"
        job.message = job.lastEngineError!!
    } else {
        message?.let { job.message = it }
        Regex("\\\"percent\\\"\\s*:\\s*(\\d+)").find(event)?.groupValues?.get(1)?.toIntOrNull()?.let {
            job.percent = it.coerceIn(0, 100)
        }
    }
    val percent = job.percent ?: 0
    if (job.message != job.lastLoggedMessage || percent / 5 > job.lastLoggedPercent / 5) {
        job.lastLoggedMessage = job.message
        job.lastLoggedPercent = percent
        ActivityLog.info("render.progress", job.message, jobContext(job, mapOf("percent" to percent)))
    }
}

private fun writeRenderJob(job: RenderJob): Path {
    val file = Files.createTempFile(jobsDirectory(), "render-", ".render-job.json")
    val model = File(System.getProperty("user.dir"), "models/modnet_photographic.onnx").takeIf(File::isFile)
    val json = """
        {"version":1,"referenceVideo":"${job.reference.path.toString().jsonEscape()}","backgroundVideo":"${job.background.path.toString().jsonEscape()}","outputVideo":"${job.output.toString().jsonEscape()}","modelPath":"${model?.absolutePath?.jsonEscape().orEmpty()}","outlinePixels":"${job.settings.outlinePixels.jsonEscape()}","scalePercent":"${job.settings.scalePercent.jsonEscape()}","horizontalPercent":"${job.settings.horizontalPercent.jsonEscape()}","bottomPercent":"${job.settings.bottomPercent.jsonEscape()}","outlineColor":"${job.settings.outlineColor.jsonEscape()}"}
    """.trimIndent()
    Files.writeString(file, json)
    return file
}

private fun validAccessToken(supplied: String): Boolean {
    val configured = accessToken()?.takeIf { it.isNotBlank() } ?: return false
    return MessageDigest.isEqual(configured.toByteArray(StandardCharsets.UTF_8), supplied.toByteArray(StandardCharsets.UTF_8))
}

private data class DotEnvConfig(val path: Path?, val values: Map<String, String>)

private fun loadDotEnv(): DotEnvConfig {
    val explicit = System.getenv("JAMAL_ENV_FILE")?.takeIf { it.isNotBlank() }?.let(Path::of)
    val discovered = explicit ?: generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
        .take(5)
        .map { it.toPath().resolve(".env") }
        .firstOrNull(Files::isRegularFile)
    if (discovered == null || !Files.isRegularFile(discovered)) return DotEnvConfig(null, emptyMap())

    val values = linkedMapOf<String, String>()
    Files.readAllLines(discovered, StandardCharsets.UTF_8).forEach { rawLine ->
        val line = rawLine.trim().removePrefix("export ").trim()
        if (line.isEmpty() || line.startsWith('#')) return@forEach
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator).trim()
        if (!key.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) return@forEach
        val rawValue = line.substring(separator + 1).trim()
        val value = when {
            rawValue.length >= 2 && rawValue.startsWith('"') && rawValue.endsWith('"') -> rawValue.substring(1, rawValue.length - 1)
                .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
            rawValue.length >= 2 && rawValue.startsWith('\'') && rawValue.endsWith('\'') -> rawValue.substring(1, rawValue.length - 1)
            else -> rawValue
        }
        values[key] = value
    }
    return DotEnvConfig(discovered.toAbsolutePath().normalize(), values)
}

private fun accessToken(): String? = System.getenv("JAMAL_ACCESS_TOKEN")
    ?: System.getProperty("jamal.access.token")
    ?: dotEnvConfig.values["JAMAL_ACCESS_TOKEN"]

private fun adminToken(): String? = System.getenv("JAMAL_ADMIN_TOKEN")
    ?: System.getProperty("jamal.admin.token")
    ?: dotEnvConfig.values["JAMAL_ADMIN_TOKEN"]
    ?: accessToken()

private fun requestContext(exchange: HttpExchange, details: Map<String, Any?> = emptyMap()) = LogContext(
    device = deviceFingerprint.take(12),
    remote = exchange.remoteAddress.address.hostAddress,
    details = details,
)

private fun jobContext(job: RenderJob, details: Map<String, Any?> = emptyMap()) = LogContext(
    device = deviceFingerprint.take(12),
    jobId = job.id,
    details = mapOf("reference" to job.reference.name, "background" to job.background.name) + details,
)

private fun requireAdmin(exchange: HttpExchange): Boolean {
    val configured = adminToken()?.takeIf { it.isNotBlank() }
    if (configured == null) {
        ActivityLog.warn("admin.unavailable", "Admin API requested without JAMAL_ADMIN_TOKEN configured", requestContext(exchange))
        respondJson(exchange, 503, "{\"error\":\"Set JAMAL_ADMIN_TOKEN and restart the app.\"}")
        return false
    }
    val supplied = decodeUtf8Base64Header(exchange, "X-Admin-Token-Base64")
        ?: exchange.requestHeaders.getFirst("X-Admin-Token").orEmpty()
    if (!MessageDigest.isEqual(configured.toByteArray(StandardCharsets.UTF_8), supplied.toByteArray(StandardCharsets.UTF_8))) {
        ActivityLog.warn("admin.auth_failed", "Invalid admin token", requestContext(exchange, mapOf("tokenSupplied" to supplied.isNotBlank())))
        exchange.responseHeaders.add("WWW-Authenticate", "JamalAdmin")
        respondJson(exchange, 401, "{\"error\":\"Invalid admin token.\"}")
        return false
    }
    return true
}

private fun decodeUtf8Base64Header(exchange: HttpExchange, name: String): String? =
    exchange.requestHeaders.getFirst(name)?.let { encoded ->
        try { String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8) }
        catch (_: IllegalArgumentException) { "" }
    }

private fun adminOverview(exchange: HttpExchange) {
    if (!requireAdmin(exchange)) return
    exchange.responseHeaders.add("Cache-Control", "no-store")
    val allJobs = jobs.values.sortedByDescending { it.queuedAt }
    val jobJson = allJobs.joinToString(",") { job ->
        """{"id":"${job.id}","reference":"${job.reference.name.jsonEscape()}","background":"${job.background.name.jsonEscape()}","state":"${job.state.name.lowercase()}","percent":${job.percent ?: "null"},"message":"${job.message.jsonEscape()}","queuedAt":${job.queuedAt},"startedAt":${job.startedAt ?: "null"},"finishedAt":${job.finishedAt ?: "null"}}"""
    }
    fun count(state: JobState) = allJobs.count { it.state == state }
    val used = UsageStore.used(deviceFingerprint)
    respondJson(exchange, 200, """{"startedAt":$serverStartedAt,"uptimeMs":${System.currentTimeMillis() - serverStartedAt},"device":"${deviceFingerprint.take(12)}","quota":{"used":$used,"limit":$FREE_RENDER_LIMIT},"counts":{"total":${allJobs.size},"queued":${count(JobState.QUEUED)},"rendering":${count(JobState.RENDERING)},"complete":${count(JobState.COMPLETE)},"failed":${count(JobState.FAILED)}},"eventCount":${ActivityLog.eventCount()},"logFile":"${ActivityLog.currentFile().toString().jsonEscape()}","jobs":[$jobJson]}""")
}

private fun adminEvents(exchange: HttpExchange) {
    if (!requireAdmin(exchange)) return
    exchange.responseHeaders.add("Cache-Control", "no-store")
    val limit = Regex("(?:^|&)limit=(\\d+)").find(exchange.requestURI.rawQuery.orEmpty())
        ?.groupValues?.get(1)?.toIntOrNull() ?: 250
    respondJson(exchange, 200, "{\"events\":${ActivityLog.recentJson(limit)}}")
}

private fun downloadActivityLog(exchange: HttpExchange) {
    if (!requireAdmin(exchange)) return
    val file = ActivityLog.currentFile()
    if (!Files.isRegularFile(file)) return respondText(exchange, 404, "No activity log exists yet")
    ActivityLog.info("admin.log_downloaded", "Administrator downloaded the current activity log", requestContext(exchange, mapOf("bytes" to Files.size(file))))
    exchange.responseHeaders.add("Content-Type", "application/x-ndjson; charset=utf-8")
    exchange.responseHeaders.add("Content-Disposition", "attachment; filename=${file.fileName}")
    exchange.responseHeaders.add("Cache-Control", "no-store")
    exchange.sendResponseHeaders(200, Files.size(file))
    Files.newInputStream(file).use { it.copyTo(exchange.responseBody) }
    exchange.close()
}

private fun localDeviceFingerprint(): String {
    val macs = try {
        Collections.list(NetworkInterface.getNetworkInterfaces()).mapNotNull { network ->
            network.hardwareAddress?.takeIf { it.isNotEmpty() }?.joinToString("") { "%02x".format(it) }
        }.distinct().sorted()
    } catch (_: Exception) { emptyList() }
    val source = if (macs.isNotEmpty()) {
        macs.joinToString("|")
    } else {
        val fallback = jamalDirectory().resolve("device-id")
        if (!Files.exists(fallback)) Files.writeString(fallback, UUID.randomUUID().toString())
        Files.readString(fallback).trim()
    }
    return sha256("jamal-device-v1|$source")
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

private object UsageStore {
    private val path get() = jamalDirectory().resolve("usage.properties")

    @Synchronized fun used(device: String): Int = load().getProperty(device, "0").toIntOrNull() ?: 0

    @Synchronized fun reserveIfAvailable(device: String, count: Int, limit: Int): Boolean {
        val values = load()
        val current = values.getProperty(device, "0").toIntOrNull() ?: 0
        if (count > limit - current) return false
        values.setProperty(device, (current + count).toString())
        val temporary = Files.createTempFile(jamalDirectory(), "usage-", ".tmp")
        Files.newOutputStream(temporary).use { values.store(it, "Jamal render usage; keys are SHA-256 device fingerprints") }
        try {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
        }
        return true
    }

    private fun load() = Properties().also { values ->
        if (Files.isRegularFile(path)) Files.newInputStream(path).use(values::load)
    }
}

private fun quotaJson(used: Int): String = "{\"limit\":$FREE_RENDER_LIMIT,\"used\":$used,\"remaining\":${(FREE_RENDER_LIMIT - used).coerceAtLeast(0)},\"tokenConfigured\":${!accessToken().isNullOrBlank()},\"device\":\"${deviceFingerprint.take(12)}\"}"

private fun resolveEngineExecutable(): File? = generateSequence(File(System.getProperty("user.dir")).canonicalFile) { it.parentFile }
    .take(5).map { File(it, "render-engine/build/jamal-render-engine") }
    .firstOrNull { it.setExecutable(true) && it.canExecute() }

private fun jamalDirectory(): Path = Path.of(System.getProperty("user.home"), ".jamal").also(Files::createDirectories)
private fun uploadsDirectory(): Path = jamalDirectory().resolve("uploads").also(Files::createDirectories)
private fun jobsDirectory(): Path = jamalDirectory().resolve("jobs").also(Files::createDirectories)

private fun exportsDirectory(): Path {
    val configured = System.getProperty("jamal.exports.dir")?.takeIf { it.isNotBlank() }?.let(Path::of)
    val home = Path.of(System.getProperty("user.home"))
    val oneDrive = home.resolve("Library/CloudStorage/OneDrive-Personal")
    val destination = configured
        ?: oneDrive.takeIf(Files::isDirectory)?.resolve("Jamal Video Compositor/Exports")
        ?: home.resolve(".jamal/exports")
    return destination.toAbsolutePath().also(Files::createDirectories)
}

private data class Multipart(
    val values: MutableMap<String, String> = mutableMapOf(),
    val files: MutableMap<String, MutableList<UploadedFile>> = mutableMapOf(),
)

private fun parseMultipart(exchange: HttpExchange): Multipart {
    val contentType = exchange.requestHeaders.getFirst("Content-Type") ?: error("Missing form data")
    val boundary = Regex("boundary=([^;]+)").find(contentType)?.groupValues?.get(1)?.trim('"') ?: error("Invalid form data")
    val bytes = exchange.requestBody.readBytes()
    val marker = "--$boundary".toByteArray()
    val headerMarker = "\r\n\r\n".toByteArray()
    val result = Multipart()
    var boundaryStart = indexOf(bytes, marker)
    require(boundaryStart >= 0) { "Invalid multipart boundary" }
    while (boundaryStart >= 0) {
        var partStart = boundaryStart + marker.size
        if (partStart + 1 < bytes.size && bytes[partStart] == '-'.code.toByte() && bytes[partStart + 1] == '-'.code.toByte()) break
        if (partStart + 1 < bytes.size && bytes[partStart] == 13.toByte() && bytes[partStart + 1] == 10.toByte()) partStart += 2
        val nextBoundary = indexOf(bytes, marker, partStart)
        if (nextBoundary < 0) break
        var partEnd = nextBoundary
        if (partEnd >= 2 && bytes[partEnd - 2] == 13.toByte() && bytes[partEnd - 1] == 10.toByte()) partEnd -= 2
        val headerEnd = indexOf(bytes, headerMarker, partStart)
        if (headerEnd < 0 || headerEnd >= partEnd) { boundaryStart = nextBoundary; continue }
        val headers = String(bytes, partStart, headerEnd - partStart, StandardCharsets.UTF_8)
        val dataStart = headerEnd + headerMarker.size
        if (dataStart > partEnd) { boundaryStart = nextBoundary; continue }
        val name = Regex("name=\"([^\"]+)\"").find(headers)?.groupValues?.get(1)
        if (name == null) { boundaryStart = nextBoundary; continue }
        val filename = Regex("filename=\"([^\"]*)\"").find(headers)?.groupValues?.get(1)
        if (filename.isNullOrEmpty()) result.values[name] = String(bytes, dataStart, partEnd - dataStart, StandardCharsets.UTF_8)
        else {
            val safeName = File(filename).name
            val destination = Files.createTempFile(uploadsDirectory(), "${Instant.now().epochSecond}-", "-${safeName.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            Files.newOutputStream(destination).use { it.write(bytes, dataStart, partEnd - dataStart) }
            result.files.getOrPut(name, ::mutableListOf) += UploadedFile(safeName, destination)
        }
        boundaryStart = nextBoundary
    }
    return result
}

private fun indexOf(source: ByteArray, target: ByteArray, from: Int = 0): Int {
    if (target.isEmpty() || from < 0 || from > source.size - target.size) return -1
    for (i in from..source.size - target.size) if (target.indices.all { source[i + it] == target[it] }) return i
    return -1
}
private fun String.extension() = substringAfterLast('.', "").lowercase()
private fun String?.orDefault(default: String) = this?.takeIf { it.isNotBlank() } ?: default
private fun String.jsonEscape() = replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")
private fun respondHtml(exchange: HttpExchange, page: String = WEB_PAGE) { val body = page.toByteArray(); exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8"); exchange.responseHeaders.add("Cache-Control", "no-store"); exchange.sendResponseHeaders(200, body.size.toLong()); exchange.responseBody.use { it.write(body) } }
private fun respondJson(exchange: HttpExchange, status: Int, body: String) { exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8"); respond(exchange, status, body) }
private fun respondText(exchange: HttpExchange, status: Int, body: String) { exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8"); respond(exchange, status, body) }
private fun respond(exchange: HttpExchange, status: Int, body: String) { val bytes = body.toByteArray(); exchange.sendResponseHeaders(status, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) } }

private val ADMIN_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Jamal — Admin</title><style>
*{box-sizing:border-box}body{margin:0;background:#0b0d11;color:#eff2f8;font:14px ui-sans-serif,system-ui,-apple-system,sans-serif}.page{max-width:1320px;margin:auto;padding:30px 24px 70px}header{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:22px}h1{font-size:32px;margin:0}h2{font-size:17px;margin:0 0 14px}.muted{color:#8e97a7}.card{background:#15181e;border:1px solid #2b303a;border-radius:14px;padding:18px}.login{max-width:480px;margin:90px auto}.login input{margin:14px 0}.toolbar{display:flex;gap:10px;align-items:center;flex-wrap:wrap}.cards{display:grid;grid-template-columns:repeat(6,1fr);gap:12px;margin-bottom:18px}.metric strong{display:block;font-size:27px;margin-top:7px}.metric span{color:#919aaa;font-size:12px;text-transform:uppercase;letter-spacing:.5px}.grid{display:grid;grid-template-columns:minmax(0,1fr);gap:18px}.table-wrap{overflow:auto;max-height:390px}table{width:100%;border-collapse:collapse;white-space:nowrap}th,td{text-align:left;padding:10px 11px;border-bottom:1px solid #292e37}th{position:sticky;top:0;background:#15181e;color:#8f98a8;font-size:11px;text-transform:uppercase}td.message{max-width:400px;overflow:hidden;text-overflow:ellipsis}.badge{display:inline-block;border-radius:6px;background:#29303a;padding:4px 7px;font-size:11px;text-transform:uppercase}.info{color:#83b7ff}.warn{color:#ffc36b}.error{color:#ff7f88}.debug{color:#8891a0}input,select{background:#0e1116;color:#eef2f8;border:1px solid #3a424f;border-radius:9px;padding:10px;font:inherit}button,a.button{border:0;border-radius:9px;background:#3478f6;color:#fff;padding:10px 14px;font-weight:700;text-decoration:none;cursor:pointer}button.secondary,a.secondary{background:#252b34}.hidden{display:none!important}.error-box{color:#ff8f96;margin-top:10px}.meta{display:flex;gap:20px;flex-wrap:wrap;color:#8992a2;font-size:12px;margin-top:9px}.events-head{display:flex;justify-content:space-between;align-items:center;gap:15px;margin-bottom:12px}.events-head input{min-width:270px}.empty{text-align:center;color:#788191;padding:30px}@media(max-width:900px){.cards{grid-template-columns:repeat(3,1fr)}}@media(max-width:560px){.cards{grid-template-columns:repeat(2,1fr)}header,.events-head{align-items:flex-start;flex-direction:column}.events-head input{min-width:0;width:100%}}
</style></head><body><main class="page"><section id="login" class="card login"><h1>Admin access</h1><p class="muted">Enter JAMAL_ADMIN_TOKEN. If it is not set, JAMAL_ACCESS_TOKEN is used.</p><input id="token" type="password" autocomplete="current-password" placeholder="Admin token"><button id="loginButton">Open dashboard</button><div class="error-box" id="loginError"></div></section>
<section id="dashboard" class="hidden"><header><div><h1>Jamal activity</h1><div class="meta"><span id="device"></span><span id="uptime"></span><span id="logFile"></span></div></div><div class="toolbar"><a class="button secondary" href="/">Open app</a><button class="secondary" id="download">Download log</button><button class="secondary" id="logout">Lock</button></div></header>
<div class="cards"><div class="card metric"><span>Total jobs</span><strong id="total">0</strong></div><div class="card metric"><span>Queued</span><strong id="queued">0</strong></div><div class="card metric"><span>Rendering</span><strong id="rendering">0</strong></div><div class="card metric"><span>Complete</span><strong id="complete">0</strong></div><div class="card metric"><span>Failed</span><strong id="failed">0</strong></div><div class="card metric"><span>Log events</span><strong id="eventCount">0</strong></div></div>
<div class="grid"><section class="card"><h2>Current session jobs</h2><div class="table-wrap"><table><thead><tr><th>Created</th><th>Job</th><th>Reference → background</th><th>State</th><th>Progress</th><th>Message</th></tr></thead><tbody id="jobs"></tbody></table></div></section>
<section class="card"><div class="events-head"><div><h2>Activity log</h2><span class="muted">Automatically refreshes every 2 seconds</span></div><div class="toolbar"><select id="level"><option value="">All levels</option><option>ERROR</option><option>WARN</option><option>INFO</option><option>DEBUG</option></select><input id="search" type="search" placeholder="Filter action, job, message…"></div></div><div class="table-wrap"><table><thead><tr><th>Time</th><th>Level</th><th>Action</th><th>Job</th><th>Message / details</th></tr></thead><tbody id="events"></tbody></table></div></section></div></section></main><script>
const byId=id=>document.getElementById(id),login=byId('login'),dashboard=byId('dashboard');let token=sessionStorage.getItem('jamalAdminToken')||'',timer=null,allEvents=[];
function adminHeaders(){const bytes=new TextEncoder().encode(token);let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);return {'X-Admin-Token-Base64':btoa(binary)}}
async function api(path){const r=await fetch(path,{headers:adminHeaders(),cache:'no-store'}),text=await r.text();let data;try{data=JSON.parse(text)}catch(_){data={error:text||'Invalid server response'}}if(!r.ok)throw Error(data.error||`Request failed (__DOLLAR__{r.status})`);return data}
function unlock(){token=byId('token').value.trim();if(!token){byId('loginError').textContent='Enter the admin token.';return}refresh().then(()=>{sessionStorage.setItem('jamalAdminToken',token);login.classList.add('hidden');dashboard.classList.remove('hidden');schedule()}).catch(e=>byId('loginError').textContent=e.message)}
byId('loginButton').onclick=unlock;byId('token').onkeydown=e=>{if(e.key==='Enter')unlock()};byId('logout').onclick=()=>{sessionStorage.removeItem('jamalAdminToken');token='';dashboard.classList.add('hidden');login.classList.remove('hidden');clearTimeout(timer)};
async function refresh(){const [overview,eventData]=await Promise.all([api('/api/admin/overview'),api('/api/admin/events?limit=500')]);renderOverview(overview);allEvents=eventData.events||[];renderEvents()}
function schedule(){clearTimeout(timer);if(!token)return;timer=setTimeout(()=>refresh().catch(e=>{if(e.message.toLowerCase().includes('token'))byId('logout').click()}).finally(()=>{if(token)schedule()}),2000)}
function renderOverview(d){for(const k of ['total','queued','rendering','complete','failed'])byId(k).textContent=d.counts[k]||0;byId('eventCount').textContent=d.eventCount;byId('device').textContent='Device '+d.device+' · quota '+d.quota.used+'/'+d.quota.limit;byId('uptime').textContent='Uptime '+duration(d.uptimeMs);byId('logFile').textContent=d.logFile;byId('jobs').innerHTML=d.jobs.length?d.jobs.map(j=>`<tr><td>__DOLLAR__{date(j.queuedAt)}</td><td title="__DOLLAR__{esc(j.id)}">__DOLLAR__{esc(j.id.slice(0,8))}</td><td>__DOLLAR__{esc(j.reference)} → __DOLLAR__{esc(j.background)}</td><td><span class="badge">__DOLLAR__{esc(j.state)}</span></td><td>__DOLLAR__{j.percent??'—'}%</td><td class="message" title="__DOLLAR__{esc(j.message)}">__DOLLAR__{esc(j.message)}</td></tr>`).join(''):'<tr><td colspan="6" class="empty">No jobs in this server session.</td></tr>'}
function renderEvents(){const level=byId('level').value,q=byId('search').value.toLowerCase(),filtered=allEvents.filter(e=>(!level||e.level===level)&&(!q||JSON.stringify(e).toLowerCase().includes(q))).reverse();byId('events').innerHTML=filtered.length?filtered.map(e=>`<tr><td>__DOLLAR__{date(e.epochMs)}</td><td class="__DOLLAR__{e.level.toLowerCase()}">__DOLLAR__{esc(e.level)}</td><td>__DOLLAR__{esc(e.action)}</td><td title="__DOLLAR__{esc(e.jobId||'')}">__DOLLAR__{esc(e.jobId?e.jobId.slice(0,8):'—')}</td><td class="message" title="__DOLLAR__{esc(JSON.stringify(e.details||{}))}">__DOLLAR__{esc(e.message)} <span class="muted">__DOLLAR__{esc(details(e.details))}</span></td></tr>`).join(''):'<tr><td colspan="5" class="empty">No matching events.</td></tr>'}
byId('level').onchange=renderEvents;byId('search').oninput=renderEvents;byId('download').onclick=async()=>{try{const r=await fetch('/api/admin/logs/download',{headers:adminHeaders()});if(!r.ok)throw Error(await r.text());const blob=await r.blob(),url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='jamal-activity.ndjson';a.click();URL.revokeObjectURL(url)}catch(e){alert(e.message)}};
function details(d){return Object.entries(d||{}).map(([k,v])=>`__DOLLAR__{k}=__DOLLAR__{v}`).join(' · ')}function date(ms){return new Date(ms).toLocaleString()}function duration(ms){const s=Math.floor(ms/1000),h=Math.floor(s/3600),m=Math.floor(s%3600/60);return h?`__DOLLAR__{h}h __DOLLAR__{m}m`:`__DOLLAR__{m}m __DOLLAR__{s%60}s`}function esc(v){const e=document.createElement('span');e.textContent=String(v??'');return e.innerHTML}
if(token){byId('token').value=token;unlock()}
</script></body></html>""".replace("__DOLLAR__", "$")

private val WEB_PAGE = """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Jamal — Video Cutout</title><style>
*{box-sizing:border-box}body{margin:0;background:#0d0f13;color:#f4f6fb;font:15px ui-sans-serif,system-ui,-apple-system,sans-serif}.page{max-width:960px;margin:auto;padding:54px 26px 80px}h1{font-size:44px;margin:0 0 7px;letter-spacing:-1.4px}h2{font-size:19px;margin:0}.lead{color:#9da4b2;line-height:1.5}.card{background:#171a20;border:1px solid #303540;border-radius:17px;padding:24px;margin:18px 0;box-shadow:0 12px 35px #0003}.inputs{display:grid;grid-template-columns:1fr 1fr;gap:16px}.picker{display:block;background:#11141a;border:1px dashed #485263;border-radius:12px;padding:17px;font-weight:700}.picker input{margin-top:10px}.file-help{display:block;color:#858e9f;font-size:12px;font-weight:400;margin-top:7px}input{width:100%;background:#0d1015;color:#eef1f7;border:1px solid #424957;border-radius:9px;padding:11px;font:inherit}input[type=file]{padding:8px}button{border:0;border-radius:10px;background:#3478f6;color:white;padding:13px 18px;font-weight:750;font-size:15px;cursor:pointer}button:disabled{background:#343944;color:#8b93a2;cursor:wait}.primary{width:100%;margin-top:20px}.settings-toggle{width:100%;display:flex;justify-content:space-between;align-items:center;background:#232832;margin-top:18px}.settings{padding:16px 2px 2px}.settings[hidden]{display:none}.settings-grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}.settings label,.token label{display:block;color:#cdd2dc;font-weight:650}.settings input,.token input{margin-top:7px}.token{margin-top:14px;padding:14px;background:#11141a;border-radius:11px}.quota{display:flex;justify-content:space-between;gap:12px;color:#aeb6c5;font-size:13px}.notice{min-height:20px;margin-top:14px;color:#8fc0ff}.upload{margin-top:10px}.bar{height:9px;background:#292e37;border-radius:99px;overflow:hidden}.bar i{display:block;height:100%;width:0;background:linear-gradient(90deg,#3478f6,#70a2ff);transition:width .35s}.jobs{display:grid;gap:12px;margin-top:20px}.job{background:#171a20;border:1px solid #303540;border-radius:14px;padding:18px}.job-head,.job-meta{display:flex;justify-content:space-between;align-items:center;gap:14px}.job-title{font-weight:750;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.badge{font-size:12px;text-transform:uppercase;letter-spacing:.6px;padding:5px 8px;background:#292f3a;border-radius:7px;color:#aeb8c8}.job .bar{margin:13px 0 10px}.job-message{color:#9fc7ff}.job-details{color:#8992a2;font-size:12px;margin-top:7px}.download{color:#8fc0ff;font-weight:750;text-decoration:none}.empty{color:#7f8795;text-align:center;padding:22px}@media(max-width:680px){.inputs,.settings-grid{grid-template-columns:1fr}.page{padding:34px 16px}h1{font-size:36px}.quota{display:block}}
</style></head><body><main class="page"><a href="/admin" style="float:right;color:#8fc0ff;text-decoration:none">Admin</a><h1>Jamal</h1><p class="lead">Queue several cutout compositions and let them render one by one.</p>
<form id="render" class="card"><div class="inputs"><label class="picker">Reference videos<input required multiple name="reference" type="file" accept="video/*,.mov,.m4v,.avi,.mkv,.webm"><span class="file-help" id="refHelp">Choose one or several videos</span></label><label class="picker">Background videos<input required multiple name="background" type="file" accept="video/*,.mov,.m4v,.avi,.mkv,.webm"><span class="file-help" id="bgHelp">Choose one or several videos</span></label></div>
<button class="settings-toggle" id="settingsToggle" type="button"><span>Composition settings</span><span id="settingsIcon">Show ▾</span></button><section class="settings" id="settings" hidden><div class="settings-grid"><label>Outline (pixels)<input name="outlinePixels" value="12" inputmode="numeric"></label><label>Person scale (% frame height)<input name="scalePercent" value="46" inputmode="numeric"></label><label>Left margin (% frame width)<input name="horizontalPercent" value="2" inputmode="numeric"></label><label>Bottom margin (% frame height)<input name="bottomPercent" value="0" inputmode="numeric"></label><label>Outline colour<input name="outlineColor" value="#FFFFFF" pattern="#[0-9A-Fa-f]{6}"></label></div></section>
<div class="token"><div class="quota"><span id="quotaText">Checking free render allowance…</span><span id="pairText">Select videos to see output count</span></div><label>Access token (needed after 3 free renders)<input name="accessToken" type="password" autocomplete="off" placeholder="Enter token for unlimited renders"></label></div>
<button class="primary" id="submitButton">Add videos to render queue</button><div class="notice" id="notice"></div><div class="upload" id="upload" hidden><div class="bar"><i id="uploadBar"></i></div></div></form>
<section><h2>Render queue</h2><div class="jobs" id="jobs"><div class="empty">No videos queued yet.</div></div></section></main><script>
const form=document.querySelector('#render'),notice=document.querySelector('#notice'),submit=document.querySelector('#submitButton'),jobsEl=document.querySelector('#jobs'),upload=document.querySelector('#upload'),uploadBar=document.querySelector('#uploadBar');
const refs=form.elements.reference,bgs=form.elements.background,active=new Map();let quota=null;
document.querySelector('#settingsToggle').onclick=()=>{const panel=document.querySelector('#settings'),opening=panel.hidden;panel.hidden=!opening;document.querySelector('#settingsIcon').textContent=opening?'Hide ▴':'Show ▾'};
function pairCount(){const a=refs.files.length,b=bgs.files.length;if(!a||!b)return 0;if(a===b)return a;if(a===1)return b;if(b===1)return a;return -1}
function selection(){document.querySelector('#refHelp').textContent=refs.files.length?refs.files.length+' selected':'Choose one or several videos';document.querySelector('#bgHelp').textContent=bgs.files.length?bgs.files.length+' selected':'Choose one or several videos';const n=pairCount();document.querySelector('#pairText').textContent=n<0?'Selections cannot be paired':n?`__DOLLAR__{n} output__DOLLAR__{n===1?'':'s'} will be created`:'Select videos to see output count'}refs.onchange=selection;bgs.onchange=selection;
function quotaLabel(q){quota=q;document.querySelector('#quotaText').textContent=`Free renders: __DOLLAR__{q.remaining} of __DOLLAR__{q.limit} left · Device __DOLLAR__{q.device}`}
fetch('/api/quota').then(r=>r.json()).then(quotaLabel).catch(()=>document.querySelector('#quotaText').textContent='Allowance unavailable');
function utf8Base64(value){const bytes=new TextEncoder().encode(value);let binary='';for(const byte of bytes)binary+=String.fromCharCode(byte);return btoa(binary)}
function uploadError(xhr){const raw=(xhr.responseText||'').trim();try{const data=JSON.parse(raw);if(data.error)return data.error}catch(_){}const text=raw.replace(/<[^>]*>/g,' ').replace(/\s+/g,' ').trim().slice(0,240);if(xhr.status===0)return'Upload connection was interrupted. Keep this page open and try again on a faster connection.';if(xhr.status===413)return'The selected videos are larger than the server or proxy upload limit.';if(xhr.status===502)return'The video server is temporarily unavailable (HTTP 502).';if(xhr.status===504)return'The upload gateway timed out (HTTP 504). Try a smaller file or a faster connection.';return`Upload failed: HTTP __DOLLAR__{xhr.status||'network error'}__DOLLAR__{text?' — '+text:''}`}
function uploadFinished(){submit.disabled=false;upload.hidden=true}
form.onsubmit=e=>{e.preventDefault();const n=pairCount();if(n<0){notice.textContent='Choose equal counts, or choose one file on either side to reuse it.';return}submit.disabled=true;notice.textContent='Uploading selected videos…';upload.hidden=false;uploadBar.style.width='0%';const xhr=new XMLHttpRequest();xhr.open('POST','/api/renders');xhr.timeout=30*60*1000;const accessToken=form.elements.accessToken.value.trim();if(accessToken)xhr.setRequestHeader('X-Access-Token-Base64',utf8Base64(accessToken));xhr.upload.onprogress=x=>{if(x.lengthComputable){const p=Math.round(x.loaded/x.total*100);uploadBar.style.width=p+'%';notice.textContent=`Uploading videos… __DOLLAR__{p}%`}};xhr.onload=()=>{uploadFinished();let d;try{d=JSON.parse(xhr.responseText)}catch(_){notice.textContent=uploadError(xhr);return}if(xhr.status<200||xhr.status>=300){notice.textContent=d.error||uploadError(xhr);return}quotaLabel(d.quota);notice.textContent=`__DOLLAR__{d.jobs.length} render__DOLLAR__{d.jobs.length===1?'':'s'} added to the queue.`;d.jobs.forEach(addJob);saveIds();pollAll()};xhr.onerror=()=>{uploadFinished();notice.textContent='Upload connection was interrupted before the server received all video data. Please retry and keep this page open.'};xhr.ontimeout=()=>{uploadFinished();notice.textContent='Upload timed out after 30 minutes. Try smaller files or a faster connection.'};xhr.onabort=()=>{uploadFinished();notice.textContent='Upload was cancelled.'};xhr.send(new FormData(form))};
function addJob(j){if(active.has(j.id))return;active.set(j.id,j);renderJob({...j,state:'queued',percent:0,message:'Waiting in render queue',queuePosition:0})}
function renderJob(j){let el=document.getElementById('job-'+j.id);if(!el){el=document.createElement('article');el.className='job';el.id='job-'+j.id;jobsEl.querySelector('.empty')?.remove();jobsEl.prepend(el)}const state=j.state||'queued',title=`__DOLLAR__{j.reference||'Reference'} → __DOLLAR__{j.background||'Background'}`,queue=state==='queued'&&j.queuePosition?`Queue position __DOLLAR__{j.queuePosition}`:'',time=j.elapsedMs?`Elapsed __DOLLAR__{duration(j.elapsedMs)}`:'',eta=j.etaMs?`About __DOLLAR__{duration(j.etaMs)} remaining`:'';el.innerHTML=`<div class="job-head"><div class="job-title" title="__DOLLAR__{escapeHtml(title)}">__DOLLAR__{escapeHtml(title)}</div><span class="badge">__DOLLAR__{escapeHtml(state)}</span></div><div class="bar"><i style="width:__DOLLAR__{j.percent||0}%"></i></div><div class="job-meta"><span class="job-message">__DOLLAR__{escapeHtml(j.message||'Queued')}__DOLLAR__{j.percent!=null?' · '+j.percent+'%':''}</span>__DOLLAR__{j.ready?`<a class="download" href="/api/renders/__DOLLAR__{j.id}/download">Download MP4</a>`:''}</div><div class="job-details">__DOLLAR__{[queue,time,eta].filter(Boolean).join(' · ')}</div>`}
async function pollAll(){const running=[...active.keys()];if(!running.length)return;await Promise.all(running.map(async id=>{try{const r=await fetch('/api/renders/'+id);if(!r.ok){if(r.status===404)active.delete(id);return}const j=await r.json();active.set(id,j);renderJob(j);if(!j.running)active.delete(id)}catch(_){}}));saveIds();if(active.size)setTimeout(pollAll,800)}
function saveIds(){localStorage.setItem('jamalJobs',JSON.stringify([...active.keys()].slice(-30)))}
function restore(){let ids=[];try{ids=JSON.parse(localStorage.getItem('jamalJobs')||'[]')}catch(_){}ids.forEach(id=>active.set(id,{id}));if(ids.length)pollAll()}
function duration(ms){const s=Math.max(0,Math.round(ms/1000)),m=Math.floor(s/60);return m?`__DOLLAR__{m}m __DOLLAR__{s%60}s`:`__DOLLAR__{s}s`}
function escapeHtml(v){const e=document.createElement('span');e.textContent=v;return e.innerHTML}restore();selection();
</script></body></html>""".replace("__DOLLAR__", "$")
