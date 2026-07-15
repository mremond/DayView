package fr.dayview.app

import java.io.File

internal actual fun createHistoryFileSystem(): HistoryFileSystem? = appContext?.let { JvmHistoryFileSystem(File(it.filesDir, "history")) }

/** File-per-day store under [dir], with atomic writes (tmp + rename). */
internal class JvmHistoryFileSystem(private val dir: File) : HistoryFileSystem {
    override fun read(name: String): String? {
        val file = File(dir, name)
        return if (file.isFile) file.readText() else null
    }

    override fun writeAtomic(
        name: String,
        text: String,
    ) {
        dir.mkdirs()
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        val target = File(dir, name)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    override fun list(): List<String> = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") }?.map { it.name } ?: emptyList()
}
