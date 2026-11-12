package net.alexsaez.cadence.library

import net.alexsaez.cadence.drive.DriveItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookGrouperTest {

    private var nextId = 0
    private fun audio(name: String) = DriveItem("id${nextId++}-$name", name, "audio/mpeg", 100)
    private fun folder(name: String, files: List<DriveItem> = emptyList(), sub: List<FolderNode> = emptyList()) =
        FolderNode("f-$name", name, files, sub)

    @Test
    fun `each loose root file is a single-file audiobook`() {
        val books = AudiobookGrouper.group(folder("root", listOf(audio("Moby Dick.m4b"), audio("1984.mp3"))))
        assertEquals(listOf("1984", "Moby Dick"), books.map { it.title })
        assertTrue(books.none { it.isMultiFile })
    }

    @Test
    fun `numbered root files with a common name form one multi-file audiobook in natural order`() {
        val root = folder(
            "root",
            listOf(
                audio("The Hobbit - Part 10.mp3"),
                audio("The Hobbit - Part 2.mp3"),
                audio("The Hobbit - Part 1.mp3"),
                audio("Unrelated.mp3"),
            ),
        )
        val books = AudiobookGrouper.group(root)
        val hobbit = books.single { it.title == "The Hobbit" }
        assertEquals(
            listOf("The Hobbit - Part 1.mp3", "The Hobbit - Part 2.mp3", "The Hobbit - Part 10.mp3"),
            hobbit.tracks.map { it.name },
        )
        assertTrue(books.single { it.title == "Unrelated" }.tracks.size == 1)
    }

    @Test
    fun `a folder of audio files is one audiobook named after the folder`() {
        val dune = folder("Dune", listOf(audio("Dune 10.mp3"), audio("Dune 1.mp3"), audio("Dune 2.mp3")))
        val book = AudiobookGrouper.group(folder("root", sub = listOf(dune))).single()
        assertEquals("Dune", book.title)
        assertEquals(listOf("Dune 1.mp3", "Dune 2.mp3", "Dune 10.mp3"), book.tracks.map { it.name })
        assertEquals("folder:f-Dune", book.id)
    }

    @Test
    fun `a folder with one file is a single-file audiobook`() {
        val book = AudiobookGrouper.group(folder("root", sub = listOf(folder("Emma", listOf(audio("emma.m4b")))))).single()
        assertEquals("Emma", book.title)
        assertFalse(book.isMultiFile)
    }

    @Test
    fun `author folders are scanned recursively`() {
        val author = folder(
            "Asimov",
            sub = listOf(
                folder("I, Robot", listOf(audio("01.mp3"), audio("02.mp3"))),
                folder("The Gods Themselves", listOf(audio("full.mp3"))),
            ),
        )
        val books = AudiobookGrouper.group(folder("root", sub = listOf(author)))
        assertEquals(listOf("I, Robot", "The Gods Themselves"), books.map { it.title })
    }

    @Test
    fun `numbered disc folders are merged into one audiobook`() {
        val foundation = folder(
            "Foundation",
            sub = listOf(
                folder("CD 2", listOf(audio("01.mp3"), audio("02.mp3"))),
                folder("CD 1", listOf(audio("02.mp3"), audio("01.mp3"))),
            ),
        )
        val book = AudiobookGrouper.group(folder("root", sub = listOf(foundation))).single()
        assertEquals("Foundation", book.title)
        assertEquals(4, book.tracks.size)
        assertTrue(book.tracks[0].id.endsWith("01.mp3") && book.tracks[0].id in foundation.subfolders[1].audioFiles.map { it.id })
    }

    @Test
    fun `folders without audio produce nothing`() {
        assertTrue(AudiobookGrouper.group(folder("root", sub = listOf(folder("Photos")))).isEmpty())
    }

    @Test
    fun `sequence keys`() {
        assertEquals("dune", AudiobookGrouper.sequenceKey("Dune - Part 03"))
        assertEquals("dune", AudiobookGrouper.sequenceKey("Dune_ch12"))
        assertEquals("harry potter 7", AudiobookGrouper.sequenceKey("Harry Potter 7 - Chapter 1"))
        assertNull(AudiobookGrouper.sequenceKey("Moby Dick"))
        assertNull(AudiobookGrouper.sequenceKey("1984"))
    }
}
