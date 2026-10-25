package pt.tiago.neonline.leveltool

import java.io.File

internal fun isPublishedCertificateFile(file: File): Boolean = file.isFile &&
    file.parentFile.name.matches(Regex("level-[0-9]{4}")) &&
    (file.name in setOf("easy.json", "medium.json", "hard.json") ||
        file.name.matches(Regex("(easy|medium|hard)-round-[23]\\.json")))

internal fun publishedCertificateCount(root: File): Int = root.resolve("certificates").walkTopDown().count(::isPublishedCertificateFile)
