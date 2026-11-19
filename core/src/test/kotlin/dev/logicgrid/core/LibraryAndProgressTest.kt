package dev.logicgrid.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ManifestJsonTest {
    private val sample = """
        {"schema":1,"count":2,"puzzles":[
          {"pid":"aaa","id":"01-kite","title":"The Wind Is Free","theme":"kite",
           "profile":"gentle","rating":2,"categories":3,"items":4,"clues":3,
           "puzzle":{"path":"puzzles/01-kite.json","sha256":"deadbeef","bytes":4153}},
          {"pid":"bbb","id":"02-regatta","title":"Regatta","theme":"regatta",
           "profile":"hard","rating":6,"categories":5,"items":6,"clues":11,
           "puzzle":{"path":"puzzles/02-regatta.json","sha256":"cafe","bytes":9000},
           "narrative":{"path":"narratives/02-regatta.json","sha256":"f00d","bytes":5000}}
        ]}
    """.trimIndent()

    @Test
    fun `reads the listing fields`() {
        val entries = ManifestJson.parse(sample)
        assertEquals(2, entries.size)
        val first = entries[0]
        assertEquals("aaa", first.pid)
        assertEquals("The Wind Is Free", first.title)
        assertEquals("3x4", first.shape)
        assertEquals(4153, first.bytes)
    }

    @Test
    fun `narrative is optional and most puzzles have none`() {
        val entries = ManifestJson.parse(sample)
        assertNull(entries[0].narrativePath)
        assertFalse(entries[0].narrated)
        assertEquals("narratives/02-regatta.json", entries[1].narrativePath)
        assertTrue(entries[1].narrated)
    }

    @Test
    fun `unknown fields do not break an older client`() {
        // The content repo can add fields; a shipped app must keep reading it.
        val withExtra = sample.replace("\"pid\":\"aaa\"", "\"pid\":\"aaa\",\"mood\":\"brisk\"")
        assertEquals("aaa", ManifestJson.parse(withExtra)[0].pid)
    }
}

class ProgressJsonTest {
    private val a = Ref("flier", "Blaise")
    private val b = Ref("fee", "$4")
    private val c = Ref("mishap", "a sudden lull")

    @Test
    fun `round trips marks, assist and solved`() {
        val p = PuzzleProgress(
            userMarks = mapOf(cellOf(a, b) to true, cellOf(a, c) to false),
            assist = true,
            solved = true,
        )
        val back = ProgressJson.decode(ProgressJson.encode(p))
        assertEquals(p, back)
    }

    @Test
    fun `cell orientation does not survive as orientation`() {
        // cellOf normalises, so storing (a,b) and reading it back must give the
        // same key as (b,a) -- otherwise a reload would double every mark.
        val encoded = ProgressJson.encode(PuzzleProgress(mapOf(cellOf(b, a) to true)))
        assertEquals(mapOf(cellOf(a, b) to true), ProgressJson.decode(encoded).userMarks)
    }

    @Test
    fun `items containing a colon survive the round trip`() {
        // Ref.parse splits on the FIRST colon, so the item keeps any others.
        val odd = Ref("time", "8:30am")
        val encoded = ProgressJson.encode(PuzzleProgress(mapOf(cellOf(odd, a) to false)))
        assertEquals(mapOf(cellOf(odd, a) to false), ProgressJson.decode(encoded).userMarks)
    }

    @Test
    fun `a corrupt record costs one puzzle, not the app`() {
        for (junk in listOf("", "   ", "not json", "{", "{\"marks\":[{\"a\":\"no-colon\"}]}")) {
            assertEquals(PuzzleProgress(), ProgressJson.decode(junk), "input: '$junk'")
        }
        assertEquals(PuzzleProgress(), ProgressJson.decode(null))
    }

    @Test
    fun `empty progress is not started`() {
        assertFalse(PuzzleProgress().started)
        assertTrue(PuzzleProgress(mapOf(cellOf(a, b) to true)).started)
        assertTrue(PuzzleProgress(solved = true).started)
    }

    @Test
    fun `board state converts both ways`() {
        val state = BoardState(mapOf(cellOf(a, b) to true), assist = true)
        assertEquals(state, PuzzleProgress.of(state, solved = false).toBoardState())
    }
}
