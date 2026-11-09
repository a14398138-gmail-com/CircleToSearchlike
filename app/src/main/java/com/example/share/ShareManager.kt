package com.example.share

import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import com.example.model.ShareTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class ShareManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _lastTextShareTarget = MutableStateFlow<ShareTarget?>(null)
    val lastTextShareTarget: StateFlow<ShareTarget?> = _lastTextShareTarget.asStateFlow()

    private val _lastImageShareTarget = MutableStateFlow<ShareTarget?>(null)
    val lastImageShareTarget: StateFlow<ShareTarget?> = _lastImageShareTarget.asStateFlow()

    private val _recentTextShareTargets = MutableStateFlow<List<ShareTarget>>(emptyList())
    val recentTextShareTargets: StateFlow<List<ShareTarget>> = _recentTextShareTargets.asStateFlow()

    private val _recentImageShareTargets = MutableStateFlow<List<ShareTarget>>(emptyList())
    val recentImageShareTargets: StateFlow<List<ShareTarget>> = _recentImageShareTargets.asStateFlow()

    // Declared before init: loadPersistedTargets() reads them.
    /** Serialized writer for the first-frame snapshot (see [quickSnapshot]). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val snapshotScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val availableTargetsCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<ShareTarget>>>()

    /** Icons are shown at <= 36dp; rendering adaptive icons at their 108dp intrinsic size wasted memory and time. */
    private val maxIconSizePx: Int =
        (ICON_MAX_SIZE_DP * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(48)

    init {
        loadPersistedTargets()
    }

    /** Share targets known without touching PackageManager, for the first frame. */
    data class QuickSnapshot(
        val lastTextTarget: ShareTarget?,
        val lastImageTarget: ShareTarget?,
        val recentTextTargets: List<ShareTarget>,
        val recentImageTargets: List<ShareTarget>
    )

    companion object {
        private const val AVAILABLE_TARGETS_TTL_MS = 5 * 60 * 1000L
        private const val ICON_MAX_SIZE_DP = 48
        private const val PREFS_NAME = "circle_ocr_share_prefs"

        // First-frame snapshot of the last text/image targets: the component
        // (same "pkg;activity" encoding as the history), the label, and the
        // already downscaled icon as a small PNG in filesDir.
        private const val SNAPSHOT_ICON_DIR = "share_target_icons"
        private const val SNAPSHOT_TEXT_KEY = "snapshot_text_target"
        private const val SNAPSHOT_TEXT_LABEL = "snapshot_text_label"
        private const val SNAPSHOT_IMAGE_KEY = "snapshot_image_target"
        private const val SNAPSHOT_IMAGE_LABEL = "snapshot_image_label"

        private fun encodeComponent(target: ShareTarget): String =
            "${target.packageName};${target.activityName ?: ""}"

        private fun iconFileName(component: String): String =
            component.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".png"

        /**
         * The last text/image share targets, available synchronously and cheaply
         * enough for the first frame: the live values when ShareManager already
         * exists in this process, otherwise the snapshot saved on disk (two small
         * PNG decodes). Loading them through PackageManager takes long enough
         * that the share button used to appear a beat after the screen.
         */
        fun quickSnapshot(context: Context): QuickSnapshot {
            instance?.let { sm ->
                return QuickSnapshot(
                    sm._lastTextShareTarget.value,
                    sm._lastImageShareTarget.value,
                    sm._recentTextShareTargets.value,
                    sm._recentImageShareTargets.value
                )
            }
            return try {
                val appContext = context.applicationContext
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val dir = File(appContext.filesDir, SNAPSHOT_ICON_DIR)
                fun read(keyPref: String, labelPref: String): ShareTarget? {
                    val component = prefs.getString(keyPref, null) ?: return null
                    val parts = component.split(";")
                    val pkg = parts[0].takeIf { it.isNotBlank() } ?: return null
                    val activity = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                    val iconFile = File(dir, iconFileName(component))
                    val icon = if (iconFile.exists()) BitmapFactory.decodeFile(iconFile.path) else null
                    return ShareTarget(
                        packageName = pkg,
                        activityName = activity,
                        appName = prefs.getString(labelPref, null) ?: pkg,
                        iconBitmap = icon?.asImageBitmap()
                    )
                }
                val text = read(SNAPSHOT_TEXT_KEY, SNAPSHOT_TEXT_LABEL)
                val image = read(SNAPSHOT_IMAGE_KEY, SNAPSHOT_IMAGE_LABEL)
                QuickSnapshot(text, image, listOfNotNull(text), listOfNotNull(image))
            } catch (e: Throwable) {
                Log.w("ShareManager", "Could not read share target snapshot", e)
                QuickSnapshot(null, null, emptyList(), emptyList())
            }
        }

        @Volatile
        private var instance: ShareManager? = null

        fun getInstance(context: Context): ShareManager {
            return instance ?: synchronized(this) {
                instance ?: ShareManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private fun loadPersistedTargets() {
        // The full share-target query (queryIntentActivities + label + icon for
        // every installed share target) is only needed as a fallback when no
        // history exists yet. It used to run unconditionally, twice, on every
        // process start, which dominated cold-start time.
        val availableText by lazy { getAvailableShareTargets("text/plain") }
        val availableImg by lazy { getAvailableShareTargets("image/*") }

        // Load Text History
        val textHistoryRaw = prefs.getString("recent_text_history", null)
        val textTargets = mutableListOf<ShareTarget>()
        if (!textHistoryRaw.isNullOrBlank()) {
            val entries = textHistoryRaw.split("|")
            for (entry in entries) {
                val parts = entry.split(";")
                if (parts.isNotEmpty()) {
                    val pkg = parts[0]
                    val act = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else null
                    val resolved = resolveTarget(pkg, act, "text/plain")
                    if (resolved != null && textTargets.none { it.packageName == pkg && it.activityName == act }) {
                        textTargets.add(resolved)
                    }
                }
            }
        }
        if (textTargets.isEmpty()) {
            textTargets.addAll(availableText.take(5))
        }
        _recentTextShareTargets.value = textTargets
        _lastTextShareTarget.value = textTargets.firstOrNull()

        // Load Image History
        val imgHistoryRaw = prefs.getString("recent_img_history", null)
        val imgTargets = mutableListOf<ShareTarget>()
        if (!imgHistoryRaw.isNullOrBlank()) {
            val entries = imgHistoryRaw.split("|")
            for (entry in entries) {
                val parts = entry.split(";")
                if (parts.isNotEmpty()) {
                    val pkg = parts[0]
                    val act = if (parts.size > 1 && parts[1].isNotBlank()) parts[1] else null
                    val resolved = resolveTarget(pkg, act, "image/png")
                    if (resolved != null && imgTargets.none { it.packageName == pkg && it.activityName == act }) {
                        imgTargets.add(resolved)
                    }
                }
            }
        }
        if (imgTargets.isEmpty()) {
            imgTargets.addAll(availableImg.take(5))
        }
        _recentImageShareTargets.value = imgTargets
        _lastImageShareTarget.value = imgTargets.firstOrNull()

        persistSnapshot()
    }

    /**
     * Returns every activity that accepts ACTION_SEND for [mimeType].
     * Results are cached per MIME type for [AVAILABLE_TARGETS_TTL_MS]: the
     * query loads a label and an icon for every share target on the device,
     * and it is needed by several call sites (fallback history, history
     * entries that no longer resolve, the target picker).
     */
    fun getAvailableShareTargets(mimeType: String, forceRefresh: Boolean = false): List<ShareTarget> {
        if (!forceRefresh) {
            availableTargetsCache[mimeType]?.let { (loadedAt, targets) ->
                if (System.currentTimeMillis() - loadedAt < AVAILABLE_TARGETS_TTL_MS) return targets
            }
        }
        val targets = queryAvailableShareTargets(mimeType)
        availableTargetsCache[mimeType] = System.currentTimeMillis() to targets
        return targets
    }

    private fun queryAvailableShareTargets(mimeType: String): List<ShareTarget> {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PackageManager.MATCH_ALL
        } else {
            PackageManager.MATCH_DEFAULT_ONLY
        }
        val resolveInfos: List<ResolveInfo> = try {
            pm.queryIntentActivities(intent, flags)
        } catch (e: Throwable) {
            emptyList()
        }

        val myPkg = appContext.packageName
        val seen = HashSet<String>()
        return resolveInfos
            .filter { it.activityInfo.packageName != myPkg }
            // De-duplicate before loading labels/icons instead of after.
            .filter { seen.add("${it.activityInfo.packageName}/${it.activityInfo.name}") }
            .map { ri ->
                val pkgName = ri.activityInfo.packageName
                val actName = ri.activityInfo.name
                val label = ri.loadLabel(pm)?.toString() ?: pkgName
                val iconDrawable = ri.loadIcon(pm)
                val iconBmp = iconDrawable?.let { drawableToBitmap(it) }

                ShareTarget(
                    packageName = pkgName,
                    activityName = actName,
                    appName = label,
                    iconBitmap = iconBmp?.asImageBitmap()
                )
            }
    }

    private fun resolveTarget(packageName: String, activityName: String?, mimeType: String): ShareTarget? {
        return try {
            val pm = appContext.packageManager
            val componentName = if (activityName != null) ComponentName(packageName, activityName) else null
            val appInfo = pm.getApplicationInfo(packageName, 0)
            val label = pm.getApplicationLabel(appInfo).toString()
            val iconDrawable = if (componentName != null) {
                try { pm.getActivityIcon(componentName) } catch (e: Throwable) { pm.getApplicationIcon(appInfo) }
            } else {
                pm.getApplicationIcon(appInfo)
            }
            val iconBitmap = drawableToBitmap(iconDrawable)?.asImageBitmap()

            ShareTarget(
                packageName = packageName,
                activityName = activityName,
                appName = label,
                iconBitmap = iconBitmap
            )
        } catch (e: Throwable) {
            getAvailableShareTargets(mimeType).find { it.packageName == packageName }
        }
    }

    fun updateLastShareTargetFromComponent(componentName: ComponentName, isImage: Boolean) {
        val target = resolveTarget(
            componentName.packageName,
            componentName.className,
            if (isImage) "image/png" else "text/plain"
        )
        if (target != null) {
            updateLastShareTarget(target, isImage)
        }
    }

    fun updateLastShareTarget(target: ShareTarget, isImage: Boolean) {
        if (isImage) {
            _lastImageShareTarget.value = target
            val currentList = _recentImageShareTargets.value.toMutableList()
            currentList.removeAll { it.packageName == target.packageName && it.activityName == target.activityName }
            currentList.add(0, target)
            val trimmed = currentList.take(10)
            _recentImageShareTargets.value = trimmed

            val serialized = trimmed.joinToString("|") { "${it.packageName};${it.activityName ?: ""}" }
            prefs.edit()
                .putString("last_img_pkg", target.packageName)
                .putString("last_img_act", target.activityName)
                .putString("recent_img_history", serialized)
                .apply()
            persistSnapshot()
        } else {
            _lastTextShareTarget.value = target
            val currentList = _recentTextShareTargets.value.toMutableList()
            currentList.removeAll { it.packageName == target.packageName && it.activityName == target.activityName }
            currentList.add(0, target)
            val trimmed = currentList.take(10)
            _recentTextShareTargets.value = trimmed

            val serialized = trimmed.joinToString("|") { "${it.packageName};${it.activityName ?: ""}" }
            prefs.edit()
                .putString("last_text_pkg", target.packageName)
                .putString("last_text_act", target.activityName)
                .putString("recent_text_history", serialized)
                .apply()
            persistSnapshot()
        }
    }

    /**
     * Saves the current last text/image targets for [quickSnapshot]. Icons are
     * rewritten each time (at most twice per process start plus once per
     * share), so an updated app icon is picked up; files of targets that are
     * no longer shown are deleted.
     */
    private fun persistSnapshot() {
        val text = _lastTextShareTarget.value
        val image = _lastImageShareTarget.value
        snapshotScope.launch {
            try {
                val dir = File(appContext.filesDir, SNAPSHOT_ICON_DIR).apply { mkdirs() }
                val keep = HashSet<String>()
                val editor = prefs.edit()
                for ((target, keyPref, labelPref) in listOf(
                    Triple(text, SNAPSHOT_TEXT_KEY, SNAPSHOT_TEXT_LABEL),
                    Triple(image, SNAPSHOT_IMAGE_KEY, SNAPSHOT_IMAGE_LABEL)
                )) {
                    if (target == null) {
                        editor.remove(keyPref).remove(labelPref)
                        continue
                    }
                    val component = encodeComponent(target)
                    editor.putString(keyPref, component).putString(labelPref, target.appName)
                    val fileName = iconFileName(component)
                    keep += fileName
                    val icon = target.iconBitmap?.asAndroidBitmap() ?: continue
                    val tmp = File(dir, "$fileName.tmp")
                    FileOutputStream(tmp).use { out -> icon.compress(Bitmap.CompressFormat.PNG, 100, out) }
                    if (!tmp.renameTo(File(dir, fileName))) tmp.delete()
                }
                editor.apply()
                dir.listFiles()?.forEach { file -> if (file.name !in keep) file.delete() }
            } catch (e: Throwable) {
                Log.w("ShareManager", "Could not save share target snapshot", e)
            }
        }
    }

    fun copyTextToClipboard(text: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Circle OCR Selected Text", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(appContext, "クリップボードにコピーしました", Toast.LENGTH_SHORT).show()
    }

    fun shareTextGeneral(text: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_TEXT, text)
            type = "text/plain"
        }

        val chooser = createTrackedChooser(sendIntent, "テキストを共有", isImage = false)
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(chooser)
    }

    fun shareTextDirect(text: String, target: ShareTarget) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_TEXT, text)
                type = "text/plain"
                if (target.activityName != null) {
                    component = ComponentName(target.packageName, target.activityName)
                } else {
                    setPackage(target.packageName)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            appContext.startActivity(intent)
            updateLastShareTarget(target, isImage = false)
            Toast.makeText(appContext, "${target.appName} に送信しました", Toast.LENGTH_SHORT).show()
        } catch (e: Throwable) {
            Log.e("ShareManager", "Direct text share failed, opening chooser", e)
            shareTextGeneral(text)
        }
    }

    suspend fun shareImageGeneral(bitmap: Bitmap, title: String = "画像を共有"): Boolean = withContext(Dispatchers.IO) {
        // Unique filename per share ensures external apps (Gemini, Google App, etc.) never reuse cached thumbnails/bitmaps
        val uniqueFilename = "share_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png"
        val uri = saveBitmapToCache(bitmap, uniqueFilename, "images")
            ?: return@withContext false

        withContext(Dispatchers.Main) {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(appContext.contentResolver, "Screenshot", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = createTrackedChooser(sendIntent, title, isImage = true)
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            appContext.startActivity(chooser)
        }
        true
    }

    suspend fun shareImageDirect(bitmap: Bitmap, target: ShareTarget): Boolean = withContext(Dispatchers.IO) {
        // Unique filename per direct share ensures target app doesn't load stale cached content
        val uniqueFilename = "crop_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png"
        val uri = saveBitmapToCache(bitmap, uniqueFilename, "crops")
            ?: return@withContext false

        withContext(Dispatchers.Main) {
            try {
                if (target.packageName.isNotEmpty()) {
                    appContext.grantUriPermission(
                        target.packageName,
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(appContext.contentResolver, "Cropped Image", uri)
                    if (target.activityName != null) {
                        component = ComponentName(target.packageName, target.activityName)
                    } else {
                        setPackage(target.packageName)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                appContext.startActivity(intent)
                updateLastShareTarget(target, isImage = true)
                Toast.makeText(appContext, "${target.appName} に送信しました", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Log.e("ShareManager", "Direct image share failed", e)
                shareImageGeneral(bitmap, "画像を共有")
            }
        }
        true
    }

    private fun createTrackedChooser(targetIntent: Intent, title: String, isImage: Boolean): Intent {
        val receiverIntent = Intent(appContext, ChosenComponentReceiver::class.java).apply {
            putExtra(ChosenComponentReceiver.EXTRA_IS_IMAGE, isImage)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_MUTABLE else 0)
        val pendingIntent = PendingIntent.getBroadcast(
            appContext,
            if (isImage) 201 else 202,
            receiverIntent,
            flags
        )
        return Intent.createChooser(targetIntent, title, pendingIntent.intentSender)
    }

    suspend fun copyImageToClipboard(bitmap: Bitmap): Boolean = withContext(Dispatchers.IO) {
        val uniqueFilename = "clip_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(6)}.png"
        val uri = saveBitmapToCache(bitmap, uniqueFilename, "crops")
            ?: return@withContext false

        withContext(Dispatchers.Main) {
            val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newUri(appContext.contentResolver, "Cropped Image", uri)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(appContext, "画像をクリップボードにコピーしました", Toast.LENGTH_SHORT).show()
        }
        true
    }

    private fun saveBitmapToCache(bitmap: Bitmap, filename: String, subDir: String): Uri? {
        return try {
            val dir = File(appContext.cacheDir, subDir)
            if (!dir.exists()) dir.mkdirs()

            // Cleanup old cache files older than 5 minutes to keep cache lean without memory leaks
            cleanupOldCacheFiles(dir)

            val file = File(dir, filename)
            java.io.BufferedOutputStream(FileOutputStream(file), 32768).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                file
            )
        } catch (e: Throwable) {
            Log.e("ShareManager", "Failed to save bitmap to cache", e)
            null
        }
    }

    private fun cleanupOldCacheFiles(dir: File) {
        try {
            val now = System.currentTimeMillis()
            val files = dir.listFiles() ?: return
            if (files.size > 10) {
                files.sortBy { it.lastModified() }
                val toDelete = files.take(files.size - 10)
                toDelete.forEach { it.delete() }
            }
            for (f in files) {
                if (now - f.lastModified() > 300_000) {
                    f.delete()
                }
            }
        } catch (e: Throwable) {
            Log.w("ShareManager", "Error cleaning old cache files", e)
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap? {
        return try {
            if (drawable is BitmapDrawable && drawable.bitmap != null) {
                return drawable.bitmap
            }
            var width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 96
            var height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 96
            val largest = maxOf(width, height)
            if (largest > maxIconSizePx) {
                val ratio = maxIconSizePx.toFloat() / largest
                width = (width * ratio).toInt().coerceAtLeast(1)
                height = (height * ratio).toInt().coerceAtLeast(1)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bitmap
        } catch (e: Throwable) {
            null
        }
    }
}
