package fr.dayview.app

/** In-memory [HistoryFileSystem] for the contribution-store tests. */
internal class FakeHistoryFileSystem : HistoryFileSystem {
    val files = mutableMapOf<String, String>()

    override fun read(name: String): String? = files[name]

    override fun writeAtomic(
        name: String,
        text: String,
    ) {
        files[name] = text
    }

    override fun list(): List<String> = files.keys.toList()
}
