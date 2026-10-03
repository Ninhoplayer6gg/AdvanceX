// SPDX-License-Identifier: MPL-2.0
package io.advancex.engine

import io.advancex.core.rom.RomCheck
import io.advancex.core.rom.RomIdentifier
import io.advancex.core.rom.RomIdentity
import io.advancex.engine.content.ContentOrigin
import io.advancex.engine.content.DirectoryContentRoot
import java.io.File

object TestFixtures {
    val repoRoot = File(System.getProperty("advancex.repoRoot", "../.."))
    val testCart = File(repoRoot, "tools/testrom/dist/advancex-testcart.gba")
    val bundledRoot = DirectoryContentRoot(File(repoRoot, "assets/advance"), ContentOrigin.Bundled)
    val demoModSource = File(repoRoot, "mods/examples/advancex-demo-pack")

    val testCartIdentity: RomIdentity by lazy { (RomIdentifier.identify(testCart) as RomCheck.Ok).identity }

    /** Identity of a hypothetical different ROM with the same game code. */
    val otherRevisionIdentity: RomIdentity by lazy {
        testCartIdentity.copy(hashes = testCartIdentity.hashes.copy(crc32 = "00000000", sha1 = "0".repeat(40), sha256 = "0".repeat(64)))
    }

    fun tempDir(name: String): File = kotlin.io.path.createTempDirectory("ax-$name").toFile()
}
