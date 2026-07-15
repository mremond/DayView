package fr.dayview.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest

/** Nom de fichier stable et adressé par contenu pour un binaire accessoire empaqueté. */
internal fun helperFileName(resourceName: String, bytes: ByteArray): String {
    val base = resourceName.trimStart('/')
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hash = digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    return "$base-$hash"
}

/**
 * Extrait un binaire accessoire empaqueté vers un emplacement stable sous
 * ~/Library/Application Support/DayView/helpers/. Le même binaire retombe
 * toujours sur le même chemin, ce qui évite de re-extraire à chaque lancement
 * et garde une identité de chemin constante pour TCC (macOS).
 */
internal object MacHelpers {
    fun extract(resourceName: String): Path {
        val bytes = checkNotNull(javaClass.getResourceAsStream(resourceName)) {
            "Bundled helper is missing from application resources: $resourceName"
        }.use { it.readBytes() }
        val target = helpersDirectory().resolve(helperFileName(resourceName, bytes))
        if (Files.isExecutable(target)) return target
        Files.write(target, bytes)
        Files.setPosixFilePermissions(
            target,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        return target
    }

    private fun helpersDirectory(): Path {
        val dir = Paths.get(
            System.getProperty("user.home"),
            "Library",
            "Application Support",
            "DayView",
            "helpers",
        )
        Files.createDirectories(dir)
        return dir
    }
}
