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

    @Test
    fun `RdpManagedSession preserves above-4K viewports under Adaptive Progressive and clamps H264 profiles to 4K`() {
        val adaptiveDefault = com.nexusts.app.core.vault.model.ProtocolSpecificSettings.Rdp()
        assertEquals(
            com.nexusts.app.core.vault.model.RdpGfxCodecProfile.ADAPTIVE_PROGRESSIVE,
            adaptiveDefault.gfxCodecProfile
        )
        assertEquals(0, adaptiveDefault.gfxCodecProfile.nativeCode)
        assertEquals(false, adaptiveDefault.enableH264Avc444)
        assertEquals(false, com.nexusts.app.protocols.rdp.bridge.RdpManagedSession.usesH264Cap(adaptiveDefault))

        // 5K viewport (5120x2880) stays native 5120x2880 under Adaptive Progressive
        val native5k = com.nexusts.app.protocols.rdp.bridge.RdpManagedSession.clampSurfaceDimensions(
            widthPx = 5120,
            heightPx = 2880,
            h264Enabled = com.nexusts.app.protocols.rdp.bridge.RdpManagedSession.usesH264Cap(adaptiveDefault)
        )
        assertEquals(5120 to 2880, native5k)

        // >8K viewport clamps proportionally to 8192 max edge under Adaptive Progressive
        val clamped10k = com.nexusts.app.protocols.rdp.bridge.RdpManagedSession.clampSurfaceDimensions(
            widthPx = 10240,
            heightPx = 5760,
            h264Enabled = false
        )
        assertEquals(8192 to 4608, clamped10k)

        // H.264 profiles (AVC420_VIDEO & AVC444_CHROMA) clamp proportionally to 3840x2160
        val avc420 = adaptiveDefault.copy(
            gfxCodecProfile = com.nexusts.app.core.vault.model.RdpGfxCodecProfile.AVC420_VIDEO
        )
        assertEquals(1, avc420.gfxCodecProfile.nativeCode)
        assertTrue(com.nexusts.app.protocols.rdp.bridge.RdpManagedSession.usesH264Cap(avc420))
        assertEquals(
            3840 to 2160,
            com.nexusts.app.protocols.rdp.bridge.RdpManagedSession.clampSurfaceDimensions(
                widthPx = 5120,
                heightPx = 2880,
                h264Enabled = com.nexusts.app.protocols.rdp.bridge.RdpManagedSession.usesH264Cap(avc420)
            )
        )

        val avc444 = adaptiveDefault.copy(
            gfxCodecProfile = com.nexusts.app.core.vault.model.RdpGfxCodecProfile.AVC444_CHROMA,
            enableH264Avc444 = true
        )
        assertEquals(2, avc444.gfxCodecProfile.nativeCode)
        assertTrue(com.nexusts.app.protocols.rdp.bridge.RdpManagedSession.usesH264Cap(avc444))

        // Extended Mouse 4/5 (Back/Forward) flags match MS-RDPBCGR TS_POINTERX_EVENT
        assertEquals(0x8000, com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge.PTRXFLAGS_DOWN)
        assertEquals(0x0001, com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge.PTRXFLAGS_BUTTON1_BACK)
        assertEquals(0x0002, com.nexusts.app.protocols.rdp.bridge.FreeRdpNativeBridge.PTRXFLAGS_BUTTON2_FORWARD)
    }

    @Test
    fun `sanitizeStagedDisplayName preserves exact txt and exe filenames across SAF URI prefixes`() {
        assertEquals(
            "notes.txt",
            com.nexusts.app.protocols.rdp.ui.sanitizeStagedDisplayName("notes.txt", 0)
        )
        assertEquals(
            "NexusSetup_v1.0.exe",
            com.nexusts.app.protocols.rdp.ui.sanitizeStagedDisplayName("NexusSetup_v1.0.exe", 0)
        )
        assertEquals(
            "installer.exe",
            com.nexusts.app.protocols.rdp.ui.sanitizeStagedDisplayName("primary:Download/installer.exe", 0)
        )
        assertEquals(
            "readme.txt",
            com.nexusts.app.protocols.rdp.ui.sanitizeStagedDisplayName("primary:readme.txt", 1)
        )
        assertEquals(
            "agent_x64.exe",
            com.nexusts.app.protocols.rdp.ui.sanitizeStagedDisplayName("raw:/storage/emulated/0/Download/agent_x64.exe", 2)
        )
        assertEquals(
            "clipboard_file_4",
            com.nexusts.app.protocols.rdp.ui.sanitizeStagedDisplayName("..", 3)
        )
    }
}


