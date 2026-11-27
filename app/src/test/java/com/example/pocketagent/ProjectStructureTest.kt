package com.example.pocketagent

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural regression gate for the MainActivity extraction program. */
class ProjectStructureTest {
    @Test
    fun mainActivityIsFocused() {
        val lines = source("MainActivity.kt").readLines().size

        assertTrue("MainActivity.kt has $lines lines; expected fewer than 800", lines < 800)
    }

    @Test
    fun controllerFilesExist() {
        listOf(
            "InputImportController.kt",
            "VoiceController.kt",
            "ChatGenerationController.kt",
            "DialogRouter.kt",
            "ChatScreenBuilder.kt",
        ).forEach { name ->
            assertTrue("$name is missing", source(name).isFile)
        }
    }

    private fun source(name: String): File {
        val moduleRelative = File("src/main/java/com/example/pocketagent", name)
        return if (moduleRelative.exists()) moduleRelative else File("app/src/main/java/com/example/pocketagent", name)
    }
}
