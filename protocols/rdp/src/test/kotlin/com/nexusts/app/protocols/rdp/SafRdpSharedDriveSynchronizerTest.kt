package com.nexusts.app.protocols.rdp

import com.nexusts.app.protocols.rdp.storage.SafRdpSharedDriveSynchronizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SafRdpSharedDriveSynchronizerTest {

    @Test
    fun `sanitizes RDPDR share names for tsclient UNC paths cleanly`() {
        assertEquals("NexusTS", SafRdpSharedDriveSynchronizer.sanitizeDriveShareName(null))
        assertEquals("NexusTS", SafRdpSharedDriveSynchronizer.sanitizeDriveShareName("   "))
        assertEquals("NexusTS", SafRdpSharedDriveSynchronizer.sanitizeDriveShareName("///$$$"))
        assertEquals(
            "Engineering_Docs_2026",
            SafRdpSharedDriveSynchronizer.sanitizeDriveShareName("  Engineering Docs.2026! ")
        )
        assertEquals(
            24,
            SafRdpSharedDriveSynchronizer.sanitizeDriveShareName("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789").length
        )
    }

    @Test
    fun `rejects path traversal and separator injection in child segments and safe child resolution`() {
        val tempRoot = Files.createTempDirectory("nexusts-rdpdr-safe").toFile()
        try {
            assertNull(SafRdpSharedDriveSynchronizer.sanitizeChildSegment(""))
            assertNull(SafRdpSharedDriveSynchronizer.sanitizeChildSegment("."))
            assertNull(SafRdpSharedDriveSynchronizer.sanitizeChildSegment(".."))
            assertNull(SafRdpSharedDriveSynchronizer.sanitizeChildSegment("nested/evil.txt"))
            assertNull(SafRdpSharedDriveSynchronizer.sanitizeChildSegment("nested\\evil.txt"))
            assertNull(SafRdpSharedDriveSynchronizer.sanitizeChildSegment("bad\u0000name.txt"))
            assertEquals("report_q4.pdf", SafRdpSharedDriveSynchronizer.sanitizeChildSegment(" report_q4.pdf "))

            assertNull(SafRdpSharedDriveSynchronizer.resolveSafeChild(tempRoot, "../escape.txt"))
            assertNull(SafRdpSharedDriveSynchronizer.resolveSafeChild(tempRoot, "sub/../../escape.txt"))
            assertNull(SafRdpSharedDriveSynchronizer.resolveSafeChild(tempRoot, ""))

            val validResolved = SafRdpSharedDriveSynchronizer.resolveSafeChild(tempRoot, "exports/2026/summary.csv")
            assertNotNull(validResolved)
            assertTrue(validResolved!!.path.startsWith(tempRoot.canonicalFile.path))
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    @Test
    fun `prepares sandboxed POSIX drive and tracks incremental snapshot changes across sync`() {
        val baseDir = Files.createTempDirectory("nexusts-rdpdr-stage").toFile()
        val mirrorDir = Files.createTempDirectory("nexusts-rdpdr-mirror").toFile()
        try {
            // Disabled with zero SAF URIs -> returns null (no unnecessary RDPDR channel mount)
            assertNull(
                SafRdpSharedDriveSynchronizer.prepareSessionDrive(
                    context = null,
                    connectionId = "conn-disabled",
                    redirectDrivesEnabled = false,
                    safUriStrings = emptyList(),
                    explicitBaseDir = baseDir
                )
            )

            // Enabled -> creates clean per-connection staging folder + README and exposes \\tsclient\NexusTS
            val prepared = SafRdpSharedDriveSynchronizer.prepareSessionDrive(
                context = null,
                connectionId = "conn:prod/win-jump-01",
                redirectDrivesEnabled = true,
                safUriStrings = emptyList(),
                explicitBaseDir = baseDir
            )
            assertNotNull(prepared)
            assertEquals("NexusTS", prepared!!.shareName)
            assertEquals("\\\\tsclient\\NexusTS", prepared.uncDisplayPath)
            assertTrue(File(prepared.localPosixDirectory, "README-NexusTS-SharedDrive.txt").isFile)
            assertTrue("README-NexusTS-SharedDrive.txt" in prepared.initialSnapshot)

            // Simulate remote Windows server writing a new file and nested artifact into \\tsclient\NexusTS
            val remoteDrop = File(prepared.localPosixDirectory, "invoices/inv-1042.txt").apply {
                parentFile?.mkdirs()
                writeText("Invoice #1042 - Paid")
            }
            assertTrue(remoteDrop.isFile)

            val updatedSnap = SafRdpSharedDriveSynchronizer.captureDirectorySnapshot(prepared.localPosixDirectory)
            val changed = SafRdpSharedDriveSynchronizer.computeModifiedRelativePaths(
                baseline = prepared.initialSnapshot,
                currentSnapshot = updatedSnap
            )
            assertEquals(listOf("invoices/inv-1042.txt"), changed)

            // Mirror incremental changes into target directory and confirm second pass is a zero-copy no-op
            val copiedFirst = SafRdpSharedDriveSynchronizer.syncPosixDirectories(
                sourceDir = prepared.localPosixDirectory,
                targetDir = mirrorDir,
                baseline = prepared.initialSnapshot
            )
            assertEquals(1, copiedFirst)
            assertEquals("Invoice #1042 - Paid", File(mirrorDir, "invoices/inv-1042.txt").readText())

            val copiedSecond = SafRdpSharedDriveSynchronizer.syncPosixDirectories(
                sourceDir = prepared.localPosixDirectory,
                targetDir = mirrorDir,
                baseline = updatedSnap
            )
            assertEquals(0, copiedSecond)
        } finally {
            baseDir.deleteRecursively()
            mirrorDir.deleteRecursively()
        }
    }

    @Test
    fun `RdpScanCodeMapper maps standard and numpad keys and never swallows OS screenshot keys`() {
        val enter = com.nexusts.app.protocols.rdp.bridge.RdpScanCodeMapper.mapAndroidKeyCode(
            android.view.KeyEvent.KEYCODE_ENTER
        )
        assertNotNull(enter)
        assertEquals(0x1C, enter!!.scanCode)
        assertEquals(false, enter.extended)

        val numpadEnter = com.nexusts.app.protocols.rdp.bridge.RdpScanCodeMapper.mapAndroidKeyCode(
            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        )
        assertNotNull(numpadEnter)
        assertEquals(0x1C, numpadEnter!!.scanCode)
        assertEquals(true, numpadEnter.extended)

        val f12 = com.nexusts.app.protocols.rdp.bridge.RdpScanCodeMapper.mapAndroidKeyCode(
            android.view.KeyEvent.KEYCODE_F12
        )
        assertNotNull(f12)
        assertEquals(0x58, f12!!.scanCode)

        // OS screenshot / hardware media keys must return null so Android/Googlebook handles them
        assertNull(
            com.nexusts.app.protocols.rdp.bridge.RdpScanCodeMapper.mapAndroidKeyCode(
                android.view.KeyEvent.KEYCODE_SYSRQ
            )
        )
        assertNull(
            com.nexusts.app.protocols.rdp.bridge.RdpScanCodeMapper.mapAndroidKeyCode(318)
        )
        assertNull(
            com.nexusts.app.protocols.rdp.bridge.RdpScanCodeMapper.mapAndroidKeyCode(
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN
            )
        )
    }
}

