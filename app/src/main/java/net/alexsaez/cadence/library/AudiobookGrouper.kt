package net.alexsaez.cadence.library

import net.alexsaez.cadence.drive.DriveItem

/** A scanned Drive folder: its audio files and its scanned sub-folders. */
data class FolderNode(
    val id: String,
    val name: String,
    val audioFiles: List<DriveItem>,
    val subfolders: List<FolderNode>,
)

/**
 * Turns a scanned folder tree into audiobooks.
 *
 * Rules (the selected folder is the "root"):
 * - Each audio file directly in the root is a single-file audiobook, unless several root files
 *   share a base name followed by a running number ("Dune - Part 1.mp3", "Dune - Part 2.mp3"),
 *   in which case they form one multi-file audiobook.
 * - A sub-folder that directly contains audio files is one audiobook named after the folder.
 *   Its files are played in natural name order ("2 - x" before "10 - x"). With a single file it
 *   is a single-file audiobook.
 * - A sub-folder without audio files whose own sub-folders are all numbered discs/parts
 *   ("CD1", "CD2"...) and only contain audio is one audiobook spanning those discs.
 * - Any other sub-folder (e.g. an author folder) is scanned recursively.
 */
object AudiobookGrouper {

    private val sequenceSuffix = Regex(
        "^(.*?)[\\s._-]*(?:part|pt|chapter|chap|ch|cd|disc|disk|track|tape|vol|volume)?[\\s._#-]*(\\d+)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun group(root: FolderNode): List<Audiobook> {
        val books = mutableListOf<Audiobook>()
        books += groupRootFiles(root)
        root.subfolders.forEach { collectFolder(it, books) }
        return books.sortedWith(compareBy(NaturalOrder) { it.title })
    }

    private fun collectFolder(folder: FolderNode, out: MutableList<Audiobook>) {
        if (folder.audioFiles.isNotEmpty()) {
            out += Audiobook(
                id = "folder:${folder.id}",
                title = folder.name,
                tracks = folder.audioFiles.sortedWith(compareBy(NaturalOrder) { it.name }).map { it.toTrack() },
            )
            folder.subfolders.forEach { collectFolder(it, out) }
            return
        }
        if (isMultiDiscBook(folder)) {
            val tracks = folder.subfolders
                .sortedWith(compareBy(NaturalOrder) { it.name })
                .flatMap { disc -> disc.audioFiles.sortedWith(compareBy(NaturalOrder) { it.name }) }
                .map { it.toTrack() }
            out += Audiobook(id = "folder:${folder.id}", title = folder.name, tracks = tracks)
            return
        }
        folder.subfolders.forEach { collectFolder(it, out) }
    }

    private fun isMultiDiscBook(folder: FolderNode): Boolean {
        val discs = folder.subfolders
        if (discs.size < 2) return false
        if (discs.any { it.subfolders.isNotEmpty() || it.audioFiles.isEmpty() }) return false
        val keys = discs.map { discKey(it.name) ?: return false }
        return keys.distinct().size == 1
    }

    /** "CD 1" -> "cd", "Disc_02" -> "disc", "Dune Part 3" -> "dune part"; null if not numbered. */
    private fun discKey(name: String): String? {
        val match = Regex("^(.*?)[\\s._#-]*\\d+\\s*$").matchEntire(name.trim()) ?: return null
        return match.groupValues[1].trim().lowercase()
    }

    private fun groupRootFiles(root: FolderNode): List<Audiobook> {
        val byKey = root.audioFiles.groupBy { sequenceKey(it.name.substringBeforeLast('.')) }
        val books = mutableListOf<Audiobook>()
        for ((key, files) in byKey) {
            if (key != null && files.size > 1) {
                val sorted = files.sortedWith(compareBy(NaturalOrder) { it.name })
                val title = displayPrefix(sorted.first().name.substringBeforeLast('.')) ?: key
                books += Audiobook(
                    id = "group:${root.id}:$key",
                    title = title,
                    tracks = sorted.map { it.toTrack() },
                )
            } else {
                files.forEach { file ->
                    books += Audiobook(
                        id = "file:${file.id}",
                        title = file.name.substringBeforeLast('.'),
                        tracks = listOf(file.toTrack()),
                    )
                }
            }
        }
        return books
    }

    /** Lower-cased base name of a numbered name ("Dune - Part 03" -> "dune"), or null. */
    fun sequenceKey(baseName: String): String? =
        displayPrefix(baseName)?.lowercase()

    private fun displayPrefix(baseName: String): String? {
        val match = sequenceSuffix.matchEntire(baseName.trim()) ?: return null
        return match.groupValues[1].trim().trimEnd('-', '_', '.', ' ').ifBlank { null }
    }

    private fun DriveItem.toTrack() = AudioTrack(id, name, mimeType, sizeBytes)
}
