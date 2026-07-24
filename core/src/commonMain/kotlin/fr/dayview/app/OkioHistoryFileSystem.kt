package fr.dayview.app

import okio.FileSystem
import okio.Path

/**
 * The [HistoryFileSystem] `:core` owns, over Okio — which runs on the JVM, Android and
 * Kotlin/Native alike, so one implementation serves every target. The directory is a
 * constructor argument rather than something this class discovers: each app knows where its
 * own history lives, and injecting it keeps `:core` free of expect/actual.
 *
 * Internal rather than public: it is only ever constructed from `macosMain` (same module) and
 * `:core`'s own `jvmTest` (an associated compilation), both of which see internals. Okio's
 * `FileSystem`/`Path` are otherwise dragged wholesale into the generated Swift/ObjC framework
 * header, which contradicts the primitives-only facade the rest of `:core` keeps toward Swift.
 * `HistoryFileSystem`/`FileDayHistoryStore` stay public and Okio-free, since `:shared`
 * implements and constructs those directly.
 */
internal class OkioHistoryFileSystem(
    private val fileSystem: FileSystem,
    private val dir: Path,
) : HistoryFileSystem {
    override fun read(name: String): String? {
        val path = dir / name
        if (fileSystem.metadataOrNull(path)?.isRegularFile != true) return null
        return fileSystem.read(path) { readUtf8() }
    }

    override fun writeAtomic(
        name: String,
        text: String,
    ) {
        fileSystem.createDirectories(dir)
        val tmp = dir / "$name.tmp"
        fileSystem.write(tmp) { writeUtf8(text) }
        fileSystem.atomicMove(tmp, dir / name)
    }

    override fun list(): List<String> = if (fileSystem.exists(dir)) {
        fileSystem.list(dir)
            .filter { fileSystem.metadataOrNull(it)?.isRegularFile == true }
            .map { it.name }
            .filterNot { it.endsWith(".tmp") }
    } else {
        emptyList()
    }
}
