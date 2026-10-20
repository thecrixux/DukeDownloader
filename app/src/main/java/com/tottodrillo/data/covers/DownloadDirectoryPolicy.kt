package com.tottodrillo.data.covers

import java.io.File

object DownloadDirectoryPolicy {
    /** A saved value may be a deliberate choice, including the old default. Never migrate it implicitly. */
    fun resolve(publicDownloads: File, savedPath: String?): String {
        if (savedPath != null) return savedPath
        val folder = File(publicDownloads, "DukeDownloader")
        if (!folder.isDirectory) folder.mkdirs()
        return folder.absolutePath
    }
}
