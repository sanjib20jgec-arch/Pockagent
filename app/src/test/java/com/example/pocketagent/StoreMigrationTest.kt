package com.example.pocketagent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreMigrationTest {
    @Test
    fun schemaVersionIsOne() = assertEquals(1, StoreMigration.SCHEMA_VERSION)

    @Test
    fun detectsTaggedJson() = assertTrue(
        StoreMigration.hasSchemaVersion("""{"schema_version":1,"id":"x"}"""),
    )

    @Test
    fun detectsLegacyJson() = assertFalse(StoreMigration.hasSchemaVersion("""{"id":"x"}"""))

    @Test
    fun taggingIsDetectable() = assertTrue(
        StoreMigration.hasSchemaVersion(StoreMigration.tagWithSchemaVersion("""{"id":"x"}""")),
    )

    @Test
    fun ignoresNestedSchemaVersion() = assertFalse(
        StoreMigration.hasSchemaVersion("""{"id":"x","data":{"schema_version":1}}"""),
    )

    @Test
    fun tagsEveryObjectInLegacyArray() {
        val tagged = StoreMigration.tagWithSchemaVersion("""[{"id":"a"},{"id":"b"}]""")

        assertTrue(StoreMigration.hasSchemaVersion(tagged))
        assertEquals(2, Regex("\"schema_version\"").findAll(tagged).count())
    }
}
