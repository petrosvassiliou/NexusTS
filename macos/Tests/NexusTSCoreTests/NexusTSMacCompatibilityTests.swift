import Foundation
@testable import NexusTSCore
import XCTest

final class NexusTSMacCompatibilityTests: XCTestCase {

    // MARK: 1. Rust FFI .nxts v2 Binary Container Roundtrip & Tamper Rejection

    func testNxtsV2VaultSealAndOpenRoundtrip() throws {
        let doc = NxtsVaultDocument.sampleStarterVault()
        let passphrase = "Correct-Horse-Battery-Staple-macOS!"

        let sealed = try NxtsVaultSerializer.seal(
            document: doc,
            passphrase: passphrase,
            kdf: .interactiveFast
        )

        XCTAssertTrue(NxtsVaultSerializer.hasNxtsMagic(sealed))
        XCTAssertEqual(Array(sealed.prefix(6)), [0x4E, 0x58, 0x54, 0x53, 0x00, 0x02])
        XCTAssertEqual(NxtsVaultSerializer.inspectRevisionHeader(sealed), UInt64(doc.revision))

        let opened = try NxtsVaultSerializer.open(sealedData: sealed, passphrase: passphrase)
        XCTAssertEqual(opened.documentId, doc.documentId)
        XCTAssertEqual(opened.name, doc.name)
        XCTAssertEqual(opened.connections.count, doc.connections.count)
        XCTAssertEqual(opened.folders.count, doc.folders.count)
        XCTAssertEqual(opened.credentials.count, doc.credentials.count)

        // Wrong passphrase must fail AES-256-GCM authentication
        XCTAssertThrowsError(try NxtsVaultSerializer.open(sealedData: sealed, passphrase: "wrong-password"))
    }

    // MARK: 2. Wire-Exact Kotlin kotlinx.serialization "_type" Discriminator Parity

    func testProtocolSettingsDiscriminatorWireParity() throws {
        let variants: [(ProtocolSpecificSettings, String)] = [
            (.rdp(RdpProtocolSettings()), "rdp"),
            (.ssh(SshProtocolSettings()), "ssh"),
            (.fileTransfer(FileTransferProtocolSettings()), "file_transfer"),
            (.vnc(VncProtocolSettings()), "vnc"),
            (.webPage(WebPageProtocolSettings()), "web_page"),
            (.cloudVirtualization(CloudVirtualizationProtocolSettings()), "cloud_virtualization"),
            (.netUtility(NetUtilityProtocolSettings()), "net_utility"),
            (.serialUart(SerialUartProtocolSettings()), "serial_uart"),
            (.containerExec(ContainerExecProtocolSettings()), "container_exec")
        ]

        for (value, expectedDiscriminator) in variants {
            let encoded = try JSONEncoder().encode(value)
            let jsonObject = try XCTUnwrap(JSONSerialization.jsonObject(with: encoded) as? [String: Any])
            XCTAssertEqual(jsonObject["_type"] as? String, expectedDiscriminator)

            let decoded = try JSONDecoder().decode(ProtocolSpecificSettings.self, from: encoded)
            XCTAssertEqual(decoded, value)
        }
    }

    // MARK: 3. Cascading Property Inheritance, SmartFolder & RFC 6238 TOTP Vectors

    func testCascadingInheritanceAndTotpGenerator() throws {
        let rootCred = VaultCredential(id: "cred-root", name: "Root Ops", username: "ops_root")
        let subCred = VaultCredential(id: "cred-prod", name: "Prod Admin", username: "prod_sre", totpSecretBase32: "JBSWY3DPEHPK3PXP")
        let parentFolder = VaultFolder(
            id: "f-prod",
            name: "Production",
            inheritable: InheritableProperties(
                credentialMode: .OVERRIDE_EXPLICIT,
                explicitCredentialId: subCred.id,
                environmentBadge: "PROD",
                customProperties: [CustomPropertyEntry(key: "datacenter", value: "eu-central-1")]
            )
        )
        let childFolder = VaultFolder(
            id: "f-k8s",
            parentFolderId: parentFolder.id,
            name: "Kubernetes Nodes",
            inheritable: InheritableProperties(credentialMode: .INHERIT_FROM_PARENT)
        )
        let leafConn = VaultConnection(
            id: "c-node-01",
            parentFolderId: childFolder.id,
            name: "k8s-worker-01",
            host: "10.20.30.40",
            protocolType: .SSH,
            tags: ["k8s", "linux"],
            inheritable: InheritableProperties(credentialMode: .INHERIT_FROM_PARENT)
        )

        let doc = NxtsVaultDocument(
            rootInheritable: InheritableProperties(credentialMode: .OVERRIDE_EXPLICIT, explicitCredentialId: rootCred.id),
            folders: [parentFolder, childFolder],
            connections: [leafConn],
            credentials: [rootCred, subCred]
        )

        let resolved = PropertyInheritanceResolver.resolve(connection: leafConn, in: doc)
        XCTAssertEqual(resolved.effectiveCredential?.id, subCred.id)
        XCTAssertEqual(resolved.effectiveEnvironmentBadge, "PROD")
        XCTAssertEqual(resolved.effectiveCustomProperties["datacenter"]?.value, "eu-central-1")

        let sf = SmartFolder(
            name: "Prod SSH",
            filter: SmartFolderFilter(includeTags: ["k8s"], includeProtocols: [.SSH], environmentFilter: "PROD")
        )
        let matched = PropertyInheritanceResolver.evaluateSmartFolder(sf, in: doc)
        XCTAssertEqual(matched.map(\.id), [leafConn.id])

        // RFC 6238 deterministic TOTP sanity check (6 digits)
        let fixedDate = Date(timeIntervalSince1970: 1_700_000_000)
        let code = try XCTUnwrap(TotpCodeGenerator.generateTotp(base32Secret: "JBSWY3DPEHPK3PXP", timestamp: fixedDate))
        XCTAssertEqual(code.count, 6)
        XCTAssertTrue(code.allSatisfy(\.isNumber))
    }

    // MARK: 4. Security-Critical Key Sequence Macro Injection Prevention

    func testKeySequencePreventsBraceKeystrokeInjectionFromPassword() {
        let trickyCred = VaultCredential(
            name: "Tricky Secret",
            username: "admin",
            secretPassword: "My{ENTER}Pass$Name$"
        )
        let conn = VaultConnection(
            name: "CoreSwitch",
            host: "192.168.1.1",
            protocolType: .SSH,
            inheritable: InheritableProperties(credentialMode: .OVERRIDE_EXPLICIT, explicitCredentialId: trickyCred.id)
        )
        let doc = NxtsVaultDocument(connections: [conn], credentials: [trickyCred])
        let profile = PropertyInheritanceResolver.resolve(connection: conn, in: doc)

        // Single-pass token expansion must NOT expand $Name$ embedded inside $EffectivePassword$
        let expandedCmd = AutomationTaskEngine.expandTokens(
            template: "login --user $EffectiveUsername$ --pass '$EffectivePassword$'",
            profile: profile
        )
        XCTAssertEqual(expandedCmd, "login --user admin --pass 'My{ENTER}Pass$Name$'")

        // Macro compiler MUST keep {ENTER} inside $EffectivePassword$ as literal text, NOT a special key!
        let steps = AutomationTaskEngine.compileKeySequence(
            macroSequence: "$EffectiveUsername${TAB}$EffectivePassword${ENTER}",
            profile: profile
        )
        XCTAssertEqual(steps.count, 4)
        XCTAssertEqual(steps[0], .text("admin"))
        XCTAssertEqual(steps[1], .specialKey(name: "TAB", bytes: Data([0x09])))
        XCTAssertEqual(steps[2], .text("My{ENTER}Pass$Name$"))
        XCTAssertEqual(steps[3], .specialKey(name: "ENTER", bytes: Data([0x0D])))
    }

    // MARK: 5. Vector Clock & 3-Way Object-Level Delta Merge + Tombstones

    func testVectorClock3WayDeltaMergeAndTombstones() {
        var androidDoc = NxtsVaultDocument.sampleStarterVault()
        var macDoc = androidDoc

        let deletedId = androidDoc.connections[0].id
        androidDoc.tombstonedObjectIds.insert(deletedId)
        androidDoc.connections.removeAll { $0.id == deletedId }
        androidDoc.vectorClock["android"] = 2

        let addedMacConn = VaultConnection(name: "mac-studio-build-node", host: "10.0.99.5", protocolType: .APPLE_SCREEN_SHARING)
        macDoc.connections.append(addedMacConn)
        macDoc.vectorClock["macos"] = 2

        let outcome = VaultDeltaConflictResolver.mergeDocuments(local: macDoc, remote: androidDoc, localDeviceId: "macos")
        XCTAssertEqual(outcome.relation, .CONCURRENT_DIVERGENCE)
        XCTAssertFalse(outcome.mergedDocument.connections.contains(where: { $0.id == deletedId }))
        XCTAssertTrue(outcome.mergedDocument.connections.contains(where: { $0.id == addedMacConn.id }))
    }

    // MARK: 6. Royal TS .rtsx & CSV Import/Export + BSP Tiling + Rust VT/Ed25519 FFI

    func testRoyalTsAndCsvExportImportAndNativeEngines() throws {
        let doc = NxtsVaultDocument.sampleStarterVault()

        let rtsxBytes = try VaultDocumentExporter.export(document: doc, format: .rtsx)
        let importedRtsx = try EnterpriseVaultFormatImporter.importData(rtsxBytes, fileName: "team.rtsx")
        XCTAssertEqual(importedRtsx.importedConnections, doc.connections.count)

        let csvBytes = try VaultDocumentExporter.export(document: doc, format: .csv)
        let importedCsv = try EnterpriseVaultFormatImporter.importData(csvBytes, fileName: "inventory.csv")
        XCTAssertEqual(importedCsv.importedConnections, doc.connections.count)

        // Rust Ed25519 Keygen + Agent Sign FFI
        let kp = try NativeSshCryptoBridge.generateEd25519Keypair(comment: "ci@nexusts-macos")
        XCTAssertTrue(kp.publicKeyOpenSsh.hasPrefix("ssh-ed25519 "))
        XCTAssertTrue(kp.sha256Fingerprint.hasPrefix("SHA256:"))
        let sig = try NativeSshCryptoBridge.signChallenge(keyId: kp.keyId, challenge: Data("handshake-payload".utf8))
        XCTAssertEqual(sig.count, 64)

        // Rust xterm-256color VT Engine FFI
        let vt = NativeVtTerminalEngine(cols: 80, rows: 24)
        let snap = try XCTUnwrap(vt.feedAndSnapshot(Data("\u{1B}]0;macOS-ZSH\u{07}\u{1B}[32mNexusTS Ready\u{1B}[0m".utf8)))
        XCTAssertEqual(snap.title, "macOS-ZSH")
        XCTAssertTrue(snap.visibleLines[0].contains("NexusTS Ready"))

        // BSP Split-Pane Tree & QuickConnect URI Parser
        var tree = WorkspacePaneNode.initialEmpty()
        let tab1 = WorkspaceSessionTab.makeLocalZshTab()
        tree = tree.insertingTab(tab1)
        let firstLeaf = try XCTUnwrap(tree.allLeafIds.first)
        let tab2 = WorkspaceSessionTab.makeLocalZshTab()
        let (splitTree, _) = tree.splittingPane(firstLeaf, orientation: .horizontal, newTab: tab2)
        XCTAssertEqual(splitTree.allTabs.count, 2)
        let pruned = splitTree.removingTab(tab1.id)
        XCTAssertEqual(pruned.allTabs.map(\.id), [tab2.id])

        let parsedRdp = try XCTUnwrap(QuickConnectParser.parseAdHocUri("rdp://CORP\\alice@win-jump.internal:3390"))
        XCTAssertEqual(parsedRdp.protocolType, .RDP)
        XCTAssertEqual(parsedRdp.domain, "CORP")
        XCTAssertEqual(parsedRdp.username, "alice")
        XCTAssertEqual(parsedRdp.port, 3390)

        let subnet = try XCTUnwrap(NetworkCalculators.calculateIpv4Subnet(cidrInput: "10.24.0.0/22"))
        XCTAssertEqual(subnet.usableHostCount, 1022)
    }

    func testV117SortOrderRdpDriveAndTabReordering() throws {
        let kotlinV117Json = """
        {
          "documentId": "doc-v117",
          "name": "v1.1.7 Parity Vault",
          "schemaVersion": 2,
          "revision": 7,
          "connections": [
            {
              "id": "rdp-v117",
              "name": "WinServer 2025",
              "host": "10.0.50.10",
              "protocol": "RDP",
              "sortOrder": 4,
              "settings": {
                "_type": "rdp",
                "port": 3389,
                "redirectDrives": true,
                "redirectedDriveSafUris": ["file:///Users/ops/SharedOps"]
              }
            }
          ]
        }
        """
        let doc = try JSONDecoder().decode(NxtsVaultDocument.self, from: Data(kotlinV117Json.utf8))
        let conn = try XCTUnwrap(doc.connections.first)
        XCTAssertEqual(conn.sortOrder, 4)
        guard case .rdp(let rdp) = conn.settings else {
            return XCTFail("Expected .rdp settings")
        }
        XCTAssertEqual(rdp.macSharedFolderPath, "/Users/ops/SharedOps")
        XCTAssertEqual(rdp.macSharedDriveName, "SharedOps")

        // Verify cross-pane & intra-pane tab drag-and-drop reordering
        let tab1 = WorkspaceSessionTab.makeLocalZshTab()
        let tab2 = WorkspaceSessionTab.makeLocalZshTab()
        let tab3 = WorkspaceSessionTab.makeLocalZshTab()
        var tree = WorkspacePaneNode.initialEmpty()
        let firstPaneId = try XCTUnwrap(tree.allLeafIds.first)
        tree = tree.insertingTab(tab1, into: firstPaneId)
        tree = tree.insertingTab(tab2, into: firstPaneId)
        let (splitTree, secondPaneId) = tree.splittingPane(firstPaneId, orientation: .horizontal, newTab: tab3)

        // Move tab1 from firstPane into secondPane before tab3
        let movedTree = splitTree.reorderingOrMovingTab(tab1.id, before: tab3.id, in: secondPaneId)
        XCTAssertEqual(movedTree.leafContaining(tabId: tab1.id), secondPaneId)
        XCTAssertEqual(movedTree.allTabs.map(\.id), [tab2.id, tab1.id, tab3.id])
    }

    func testSha256UpdateManifestVerification() {
        let payload = Data("NexusTS-Universal2-DMG-Binary-Test".utf8)
        let validManifest = """
        adf8b254e0028a4790d310e39a6ae8af49e5bac34c341c90b51497e52ae38bad  NexusTS-v1.1.8-macOS-Universal.dmg
        """
        let wrongManifest = """
        0000000000000000000000000000000000000000000000000000000000000000  NexusTS-v1.1.8-macOS-Universal.dmg
        """
        XCTAssertTrue(
            MacGitHubReleaseUpdater.verifySha256Manifest(
                fileData: payload,
                expectedAssetName: "NexusTS-v1.1.8-macOS-Universal.dmg",
                manifestText: validManifest
            )
        )
        XCTAssertFalse(
            MacGitHubReleaseUpdater.verifySha256Manifest(
                fileData: payload,
                expectedAssetName: "NexusTS-v1.1.8-macOS-Universal.dmg",
                manifestText: wrongManifest
            )
        )
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("1.1.8", isNewerThan: "1.1.7"))
        XCTAssertFalse(MacGitHubReleaseUpdater.compareSemver("1.1.7", isNewerThan: "1.1.7"))
    }
}
