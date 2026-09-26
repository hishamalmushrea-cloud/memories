package com.memorymap.testing

import java.io.File

/**
 * Locates a file in the repository from a unit test.
 *
 * Gradle runs unit tests with the working directory set to the module, so a
 * path like `supabase/schema.sql` is one level up. Rather than hard-code that
 * depth, this walks up until the file appears, which keeps working if the tests
 * are ever run from the repository root instead.
 */
object RepoFiles {

    fun read(relativePath: String): String = find(relativePath).readText()

    fun find(relativePath: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("Could not find $relativePath at or above ${File("").absoluteFile}")
    }
}
