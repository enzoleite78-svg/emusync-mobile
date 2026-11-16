package app.emusyncmobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class CloudFilesTest {
    private val sample = """
        {"games":[
          {"id":"aaa","b":"God of War II","as":true,
           "sl":{"pc1":"C:\\Saves\\PCSX2"},"lsf":"pc1",
           "lst":"2026-11-10T21:03:11.1234567Z","lwt":"2026-11-10T20:59:40.555Z",
           "sb":524288,"mlgb":null,"campoFuturo":42},
          {"id":"bbb","b":"Crisis Core","as":false,"sl":null,"lsf":null,"lst":null,"lwt":null,"sb":0,"mlgb":null}
        ],"outro":"x"}
    """.trimIndent()

    @Test
    fun `le campos abreviados do EmuSync`() {
        val list = GameListFile.parse(sample)
        val gow = list.find("aaa")!!
        assertEquals("God of War II", gow.name)
        assertTrue(gow.autoSync)
        assertEquals("C:\\Saves\\PCSX2", gow.locations["pc1"])
        assertEquals(524288L, gow.storageBytes)

        val cc = list.find("bbb")!!
        assertNull(cc.lastSyncTime)
        assertTrue(cc.locations.isEmpty())
    }

    @Test
    fun `atualizar um jogo preserva campos desconhecidos e outros jogos`() {
        val list = GameListFile.parse(sample)
        val updated = list.replace(list.find("aaa")!!.afterUpload("cel1", "2026-11-16T10:00:00.0000000Z", 100))
        val text = updated.encode()
        assertTrue(text.contains("\"campoFuturo\":42"))
        assertTrue(text.contains("\"outro\":\"x\""))
        val again = GameListFile.parse(text)
        assertEquals("cel1", again.find("aaa")!!.lastSyncedFrom)
        assertEquals("Crisis Core", again.find("bbb")!!.name)
    }

    @Test
    fun `registra o celular em sync-sources sem mexer nos outros`() {
        val file = SyncSourcesFile.parse("""{"sources":[{"id":"pc1","name":"PC","storageProvider":1,"osPlatform":1}]}""")
        val added = file.upsertDevice("cel1", "Android - X")!!
        assertTrue(added.contains("pc1"))
        assertEquals("Android - X", added.nameOf("cel1"))
        assertTrue(added.encode().contains("\"osPlatform\":2"))
        assertNull(added.upsertDevice("cel1", "Android - X"))
    }
}

class DotNetTimeTest {
    @Test
    fun `formata com 7 casas decimais como o dotnet`() {
        val s = DotNetTime.format(Instant.parse("2026-11-16T10:20:30.123456789Z"))
        assertEquals("2026-11-16T10:20:30.1234567Z", s)
    }

    @Test
    fun `compara datas com formatos diferentes`() {
        assertTrue(DotNetTime.same("2026-11-10T20:59:40.5550000Z", "2026-11-10T20:59:40.555Z"))
        assertTrue(DotNetTime.same("2026-11-10T20:59:40", "2026-11-10T20:59:40Z"))
    }
}

class ZipPathsTest {
    @Test
    fun `converte barras do Windows`() {
        assertEquals(listOf("SAVEDATA", "ULUS10041", "DATA.BIN"), ZipPaths.normalize("SAVEDATA\\ULUS10041\\DATA.BIN"))
    }

    @Test
    fun `ignora diretorios`() {
        assertNull(ZipPaths.normalize("SAVEDATA/"))
    }

    @Test(expected = SecurityException::class)
    fun `bloqueia zip slip`() {
        ZipPaths.normalize("..\\..\\evil.txt")
    }
}

class SyncDeciderTest {
    private fun game(lwt: String?, sb: Long = 1000) = GameListFile.parse(
        """{"games":[{"id":"g","b":"Jogo","lwt":${lwt?.let { "\"$it\"" } ?: "null"},"lst":"2026-01-01T00:00:00Z","sb":$sb}]}"""
    ).find("g")!!

    private val files = listOf(FileEntry("a.sav", 10, 1000))
    private val scan = LocalScan(true, files)
    private val empty = LocalScan(true, emptyList())
    private val t1 = "2026-11-10T10:00:00.0000000Z"
    private val t2 = "2026-11-11T10:00:00.0000000Z"

    @Test fun `sem pasta`() = assertEquals(SyncAction.NO_FOLDER, SyncDecider.decide(game(t1), true, null, null))

    @Test fun `sem zip na nuvem e com saves locais pede PC primeiro`() =
        assertEquals(SyncAction.NEEDS_PC_FIRST, SyncDecider.decide(game(null), false, scan, null))

    @Test fun `primeira vez com pasta vazia baixa`() =
        assertEquals(SyncAction.DOWNLOAD, SyncDecider.decide(game(t1), true, empty, null))

    @Test fun `primeira vez com nuvem vazia envia`() =
        assertEquals(SyncAction.UPLOAD, SyncDecider.decide(game(t1, sb = 0), true, scan, null))

    @Test fun `primeira vez com saves dos dois lados e conflito`() =
        assertEquals(SyncAction.CONFLICT, SyncDecider.decide(game(t1), true, scan, null))

    @Test fun `nada mudou`() =
        assertEquals(SyncAction.IN_SYNC, SyncDecider.decide(game(t1), true, scan, LastSync(t1, scan.fingerprint)))

    @Test fun `so o celular mudou envia`() =
        assertEquals(SyncAction.UPLOAD, SyncDecider.decide(game(t1), true, scan, LastSync(t1, "antigo")))

    @Test fun `so a nuvem mudou baixa`() =
        assertEquals(SyncAction.DOWNLOAD, SyncDecider.decide(game(t2), true, scan, LastSync(t1, scan.fingerprint)))

    @Test fun `os dois mudaram e conflito`() =
        assertEquals(SyncAction.CONFLICT, SyncDecider.decide(game(t2), true, scan, LastSync(t1, "antigo")))

    @Test fun `pasta local esvaziada nunca envia sozinha`() =
        assertEquals(SyncAction.CONFLICT, SyncDecider.decide(game(t1), true, empty, LastSync(t1, scan.fingerprint)))
}
