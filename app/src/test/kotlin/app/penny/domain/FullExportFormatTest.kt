package app.penny.domain

import app.penny.core.event.PennyJson
import app.penny.domain.ops.FullExport
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** SY-07: an export always names the app and its format version, and older unversioned files still read. */
class FullExportFormatTest : FunSpec({
    test("app and schema are written even though they are the defaults") {
        val json = PennyJson.encodeToString(FullExport.serializer(), FullExport(schema = FullExport.SCHEMA, exportedAt = 1, profileId = "p", events = emptyList()))
        json shouldContain "\"app\""
        json shouldContain "\"schema\":${FullExport.SCHEMA}"
    }

    test("a file without app or schema (written before they were always included) reads as version 2") {
        val old = PennyJson.decodeFromString(FullExport.serializer(), """{"exportedAt":1,"profileId":"p","events":[]}""")
        old.schema shouldBe 2
        old.app shouldBe app.penny.core.Brand.EXPORT_APP_ID
    }
})
