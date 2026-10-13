package com.gujeff.findmyphone.wake

import android.content.Context
import android.net.Uri
import org.vosk.Model
import java.io.File
import java.util.zip.ZipInputStream

/** Unpacks Vosk model zips (bundled in assets or imported by the user) into app-private storage. */
object ModelManager {
    private const val MARKER = ".unpacked"

    fun dirFor(context: Context, modelId: String) = File(context.filesDir, "models/$modelId")

    fun isInstalled(context: Context, modelId: String) = File(dirFor(context, modelId), MARKER).exists()

    @Synchronized
    fun ensureBundled(context: Context, modelId: String): File {
        val dir = dirFor(context, modelId)
        if (!File(dir, MARKER).exists()) {
            context.assets.open("models/model-$modelId.zip").use { unzip(it, dir) }
        }
        return dir
    }

    @Synchronized
    fun importZip(context: Context, uri: Uri): File {
        val dir = dirFor(context, "import")
        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open file")
        input.use { unzip(it, dir) }
        check(File(dir, "am/final.mdl").exists() || File(dir, "final.mdl").exists()) {
            dir.deleteRecursively(); "Not a Vosk model (am/final.mdl missing)"
        }
        return dir
    }

    fun open(context: Context, modelId: String): Model {
        val dir = if (modelId == "import") dirFor(context, modelId) else ensureBundled(context, modelId)
        require(isInstalled(context, modelId)) { "Model '$modelId' is not installed" }
        return Model(dir.absolutePath)
    }

    /** Extracts a zip; if it wraps everything in one top-level folder (Vosk's layout), that folder is flattened away. */
    private fun unzip(input: java.io.InputStream, dest: File) {
        dest.deleteRecursively()
        dest.mkdirs()
        val destPath = dest.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val out = File(dest, entry.name)
                require(out.canonicalPath.startsWith(destPath)) { "Unsafe zip entry" } // zip-slip guard
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val children = dest.listFiles().orEmpty()
        if (children.size == 1 && children[0].isDirectory) {
            val inner = children[0]
            inner.listFiles().orEmpty().forEach { it.renameTo(File(dest, it.name)) }
            inner.delete()
        }
        File(dest, MARKER).writeText("ok")
    }
}
