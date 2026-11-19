package com.laksh.coffee.catalog

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** shared/ ships inside the APK and the web app: data and docs only, and small. */
class SharedDirHygieneTest {

    @Test
    fun sharedHoldsOnlyJsonAndMarkdownUnderTwoMegabytes() {
        // Unit tests run with the module directory as the working directory.
        val shared = listOf(File("../shared"), File("shared")).first { it.isDirectory }
        val files = shared.walkTopDown().filter { it.isFile }.toList()
        assertTrue("shared/ is empty", files.isNotEmpty())
        files.forEach { f ->
            assertTrue("shared/ holds ${f.path}", f.extension == "json" || f.extension == "md")
        }
        val total = files.sumOf { it.length() }
        assertTrue("shared/ is $total bytes", total < 2L * 1024 * 1024)
    }
}
