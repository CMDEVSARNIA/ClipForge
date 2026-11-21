package com.clipforge.desktop

import java.io.File

/** Where ClipForge keeps its library, settings and caches on each desktop OS. */
object AppDirs {
    enum class Os { WINDOWS, MAC, LINUX }

    fun os(name: String = System.getProperty("os.name").orEmpty()): Os = when {
        name.startsWith("Windows", ignoreCase = true) -> Os.WINDOWS
        name.startsWith("Mac", ignoreCase = true) || name.startsWith("Darwin", ignoreCase = true) -> Os.MAC
        else -> Os.LINUX
    }

    /**
     * Windows: %LOCALAPPDATA%\ClipForge · macOS: ~/Library/Application Support/ClipForge ·
     * Linux: $XDG_DATA_HOME/clipforge (default ~/.local/share/clipforge).
     * The `clipforge.data.dir` system property overrides it (tests, portable installs).
     */
    fun dataDir(
        os: Os = os(),
        env: (String) -> String? = System::getenv,
        home: String = System.getProperty("user.home"),
        override: String? = System.getProperty("clipforge.data.dir"),
    ): File {
        if (!override.isNullOrBlank()) return File(override)
        return when (os) {
            Os.WINDOWS -> {
                val base = env("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
                    ?: env("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: File(home, "AppData${File.separator}Local").path
                File(base, "ClipForge")
            }
            Os.MAC -> File(home, "Library/Application Support/ClipForge")
            Os.LINUX -> File(env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, "clipforge")
        }
    }

    val data: File by lazy { dataDir().apply { mkdirs() } }
    val thumbs: File get() = File(data, "thumbs").apply { mkdirs() }
}
