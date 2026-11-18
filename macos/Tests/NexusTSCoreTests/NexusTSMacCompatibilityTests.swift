import AppKit
import CoreFoundation
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
        let starter = NxtsVaultDocument.sampleStarterVault()
        XCTAssertTrue(starter.connections.isEmpty, "v1.2.0 starter vault must contain zero example connections")
        XCTAssertTrue(starter.folders.isEmpty, "v1.2.0 starter vault must contain zero example folders")
        XCTAssertTrue(starter.credentials.isEmpty, "v1.2.0 starter vault must contain zero example credentials")

        let sharedConn = VaultConnection(id: "c-shared-01", name: "shared-bastion", host: "10.0.10.5", protocolType: .SSH)
        var androidDoc = NxtsVaultDocument(connections: [sharedConn])
        var macDoc = androidDoc

        let deletedId = sharedConn.id
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
        let doc = NxtsVaultDocument(
            name: "Roundtrip Fleet",
            folders: [VaultFolder(id: "f-eu", name: "Europe")],
            connections: [
                VaultConnection(id: "c-rdp", parentFolderId: "f-eu", name: "EU-DC01", host: "10.10.0.10", protocolType: .RDP),
                VaultConnection(id: "c-ssh", parentFolderId: "f-eu", name: "EU-Bastion", host: "10.10.0.20", protocolType: .SSH)
            ]
        )

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
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.3.0-beta.2", isNewerThan: "0.3.0-beta.1"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.3.0-beta.3", isNewerThan: "0.3.0-beta.2"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.3.0-beta.4", isNewerThan: "0.3.0-beta.3"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.3.0-beta.5", isNewerThan: "0.3.0-beta.4"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.3.0-beta.10", isNewerThan: "0.3.0-beta.5"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.3.0", isNewerThan: "0.3.0-beta.1"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.4.0", isNewerThan: "0.3.0"))
        XCTAssertFalse(MacGitHubReleaseUpdater.compareSemver("0.3.0-beta.1", isNewerThan: "0.3.0"))
        XCTAssertFalse(MacGitHubReleaseUpdater.compareSemver("1.2.0", isNewerThan: "0.3.0-beta.1"))
        XCTAssertFalse(MacGitHubReleaseUpdater.compareSemver("1.2.1-beta.2", isNewerThan: "0.3.0-beta.1"))
        XCTAssertFalse(MacGitHubReleaseUpdater.compareSemver("1.1.9", isNewerThan: "0.3.0"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("1.3.0", isNewerThan: "0.3.0"))
    }

    func testMacWorkspaceShortcutsUniquenessAndLocalizedLabels() {
        let allKeycaps = MacWorkspaceShortcut.allCases.map(\.keycap)
        XCTAssertEqual(
            Set(allKeycaps).count,
            allKeycaps.count,
            "Every macOS keyboard shortcut must have a unique keycap without collisions"
        )
        XCTAssertEqual(MacWorkspaceShortcut.newConnection.keycap, "⌘N")
        XCTAssertEqual(MacWorkspaceShortcut.localTerminal.keycap, "⌘T")
        XCTAssertEqual(MacWorkspaceShortcut.quickConnect.keycap, "⌘K")
        XCTAssertEqual(MacWorkspaceShortcut.splitRight.keycap, "⌘D")
        XCTAssertEqual(MacWorkspaceShortcut.splitDown.keycap, "⇧⌘D")
        XCTAssertEqual(MacWorkspaceShortcut.broadcastInput.keycap, "⇧⌘B")
        XCTAssertEqual(MacWorkspaceShortcut.toggleInspector.keycap, "⌥⌘I")
        XCTAssertEqual(MacWorkspaceShortcut.toggleSidebar.keycap, "⌃⌘S")

        for lang in NexusAppLanguage.allCases {
            let catalog = LocalizedWorkspaceStrings.catalog(for: lang)
            XCTAssertFalse(catalog.quickConnectTitle.isEmpty)
            XCTAssertFalse(catalog.workspaceShortcutsTitle.isEmpty)
            XCTAssertFalse(catalog.toggleSidebar.isEmpty)
            XCTAssertFalse(catalog.fullScreenSession.isEmpty)
            for shortcut in MacWorkspaceShortcut.allCases {
                XCTAssertFalse(shortcut.title(in: catalog).isEmpty)
                XCTAssertFalse(shortcut.menuTitle(in: catalog).isEmpty)
            }
            // Ensure Overview cheat-sheet labels match canonical catalog labels 1-to-1 (e.g., "Local Terminal" / "Lokales Terminal", never "Local Shell")
            XCTAssertEqual(MacWorkspaceShortcut.localTerminal.title(in: catalog), catalog.localTerminal)
            XCTAssertEqual(MacWorkspaceShortcut.newConnection.title(in: catalog), catalog.newConnection)
            XCTAssertEqual(MacWorkspaceShortcut.broadcastInput.title(in: catalog), catalog.broadcastInput)
            XCTAssertEqual(MacWorkspaceShortcut.toggleInspector.title(in: catalog), catalog.inspectorTitle)
        }
    }

    func testV118LocalizationUpgradeVersionAndVaultReplacementCompatibility() throws {
        XCTAssertEqual(MacGitHubReleaseUpdater.currentVersion, "0.8.10")
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.8.10", isNewerThan: "0.8.9"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.8.10", isNewerThan: "0.8.8"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.8.10", isNewerThan: "0.8.7"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("0.8.10", isNewerThan: "0.3.0-beta.6"))
        XCTAssertEqual(LocalizedWorkspaceStrings.catalog(for: .en).studios, "Manage")
        XCTAssertEqual(LocalizedWorkspaceStrings.catalog(for: .en).logOffSession, "Log Off Session")

        for lang in NexusAppLanguage.allCases {
            let catalog = LocalizedWorkspaceStrings.catalog(for: lang)
            XCTAssertFalse(catalog.studios.isEmpty)
            XCTAssertNotEqual(catalog.studios.lowercased(), "studios")
            XCTAssertFalse(catalog.renameFolder.isEmpty)
            XCTAssertFalse(catalog.startAllRdp.isEmpty)
            XCTAssertFalse(catalog.logOffSession.isEmpty)
            XCTAssertFalse(catalog.deleteFolderAndContents.isEmpty)
            XCTAssertFalse(catalog.deleteFolderKeepContents.isEmpty)
            XCTAssertFalse(catalog.royalImportTitle.isEmpty)
            XCTAssertFalse(catalog.resetDatabase.isEmpty)
            XCTAssertFalse(catalog.systemCredentialsFolder.isEmpty)
            XCTAssertFalse(catalog.newSmartFolder.isEmpty)
            XCTAssertFalse(catalog.editConnection.isEmpty)
        }

        // Verify a persisted v1.1.7 .nxts v2 vault with nested folders + RDP/SSH hosts upgrades seamlessly in v1.2.1-beta.1
        let rootFolder = VaultFolder(id: "f-dc", name: "Datacenter Europe", sortOrder: 0)
        let subFolder = VaultFolder(id: "f-win", parentFolderId: rootFolder.id, name: "Windows RDS Farm", sortOrder: 1)
        let rdp1 = VaultConnection(id: "c-rdp-1", parentFolderId: rootFolder.id, name: "DC01", host: "10.10.1.10", protocolType: .RDP)
        let rdp2 = VaultConnection(id: "c-rdp-2", parentFolderId: subFolder.id, name: "RDS-Broker", host: "10.10.2.20", protocolType: .WINDOWS_TERMINAL_SERVICES)
        let ssh1 = VaultConnection(id: "c-ssh-1", parentFolderId: subFolder.id, name: "Linux-Jump", host: "10.10.2.30", protocolType: .SSH)

        let v117Doc = NxtsVaultDocument(
            documentId: "doc-upgrade-v117-to-v118",
            name: "Enterprise Upgrade Vault",
            revision: 17,
            folders: [rootFolder, subFolder],
            connections: [rdp1, rdp2, ssh1]
        )
        let sealed = try NxtsVaultSerializer.seal(
            document: v117Doc,
            passphrase: NxtsVaultSerializer.defaultTransparentPassphrase,
            kdf: .interactiveFast
        )
        let openedInV120 = try NxtsVaultSerializer.open(
            sealedData: sealed,
            passphrase: NxtsVaultSerializer.defaultTransparentPassphrase
        )
        XCTAssertEqual(openedInV120.documentId, "doc-upgrade-v117-to-v118")
        XCTAssertEqual(openedInV120.folders.count, 2)
        XCTAssertEqual(openedInV120.connections.count, 3)
    }

    func testIntelligentSidebarProtocolGroupingSuppressesSingleTypeAndGroupsMixedTypes() {
        let rdpOnly = [
            VaultConnection(id: "r1", parentFolderId: "f-rdp", name: "RDS-01", host: "10.0.1.1", protocolType: .RDP, sortOrder: 1),
            VaultConnection(id: "r2", parentFolderId: "f-rdp", name: "RDS-02", host: "10.0.1.2", protocolType: .WINDOWS_TERMINAL_SERVICES, sortOrder: 2)
        ]
        let singleGroups = IntelligentConnectionGrouper.group(rdpOnly)
        XCTAssertEqual(singleGroups.count, 1)
        XCTAssertEqual(singleGroups.first?.groupBadge, "RDP")
        XCTAssertFalse(singleGroups.first?.showsHeader ?? true, "Homogeneous RDP folder must not show redundant RDP header")

        let mixed = [
            VaultConnection(id: "s1", parentFolderId: "f-mix", name: "bastion-01", host: "10.0.2.10", protocolType: .SSH, sortOrder: 1),
            VaultConnection(id: "r3", parentFolderId: "f-mix", name: "DC-Core-01", host: "10.0.2.20", protocolType: .RDP, sortOrder: 2),
            VaultConnection(id: "s2", parentFolderId: "f-mix", name: "bastion-02", host: "10.0.2.11", protocolType: .SSH, sortOrder: 3)
        ]
        let mixedGroups = IntelligentConnectionGrouper.group(mixed)
        XCTAssertEqual(mixedGroups.count, 2)
        XCTAssertEqual(mixedGroups.map(\.groupBadge), ["RDP", "SSH"])
        XCTAssertTrue(mixedGroups.allSatisfy(\.showsHeader), "Mixed-protocol folder must group by connection type with subtle headers")
        XCTAssertEqual(mixedGroups[0].connections.map(\.id), ["r3"])
        XCTAssertEqual(mixedGroups[1].connections.map(\.id), ["s1", "s2"])
    }

    func testV120CleanVaultRoyalTsPlacementSmartFoldersAndErrorHandling() throws {
        // 1. Legacy starter demo objects stripped while preserving user items
        let dirtyDoc = NxtsVaultDocument(
            folders: [
                VaultFolder(id: "folder-prod-core", name: "Demo Prod"),
                VaultFolder(id: "f-creds-root", name: "Credentials"),
                VaultFolder(id: "f-real", name: "Customer Prod")
            ],
            connections: [
                VaultConnection(id: "conn-bastion-01", name: "Demo Bastion", host: "10.0.0.1", protocolType: .SSH),
                VaultConnection(id: "c-real", parentFolderId: "f-real", name: "Real-Host", host: "10.99.0.1", protocolType: .RDP)
            ],
            credentials: [
                VaultCredential(id: "cred-prod-ops", name: "Demo Ops", username: "ops"),
                VaultCredential(id: "cred-real", parentFolderId: "f-creds-root", name: "Real Admin", username: "admin")
            ]
        )
        let sanitized = VaultHierarchySanitizer.sanitizeVaultDocument(dirtyDoc)
        XCTAssertEqual(sanitized.folders.map { $0.id }, ["f-real"])
        XCTAssertEqual(sanitized.connections.map { $0.id }, ["c-real"])
        XCTAssertEqual(sanitized.credentials.map { $0.id }, ["cred-real"])
        XCTAssertNil(sanitized.credentials.first?.parentFolderId)

        // 2. Royal TS placement modes (createNewFolder, placeAtRoot, replaceExisting) + built-in Connections unwrap
        let importedRoyal = NxtsVaultDocument(
            name: "Zurich DC",
            folders: [
                VaultFolder(id: "r-conns", name: "Connections"),
                VaultFolder(id: "r-zrh", parentFolderId: "r-conns", name: "Zurich Core")
            ],
            connections: [
                VaultConnection(id: "r-c1", parentFolderId: "r-zrh", name: "ZRH-DC01", host: "10.41.0.10", protocolType: .RDP)
            ]
        )
        let inNewFolder = RoyalTsImportMerger.applyPlacement(
            existing: sanitized,
            importedRaw: importedRoyal,
            mode: .createNewFolder,
            newFolderName: "Zurich Import"
        )
        let wrapper = try XCTUnwrap(inNewFolder.folders.first(where: { $0.name == "Zurich Import" }))
        let zrhFolder = try XCTUnwrap(inNewFolder.folders.first(where: { $0.name == "Zurich Core" }))
        XCTAssertEqual(zrhFolder.parentFolderId, wrapper.id)
        XCTAssertEqual(inNewFolder.connections.count, 2)

        let replaced = RoyalTsImportMerger.applyPlacement(
            existing: sanitized,
            importedRaw: importedRoyal,
            mode: .replaceExisting
        )
        XCTAssertEqual(replaced.folders.map { $0.name }, ["Zurich Core"])
        XCTAssertEqual(replaced.connections.map { $0.name }, ["ZRH-DC01"])

        // 3. SmartFolder scopeFolderId, excludeTags, and regex validation
        XCTAssertNil(PropertyInheritanceResolver.validateSmartFolderRegex("^10\\.41\\..*"))
        XCTAssertNotNil(PropertyInheritanceResolver.validateSmartFolderRegex("[unclosed"))
        let smart = SmartFolder(
            name: "Scoped Zurich RDP",
            filter: SmartFolderFilter(
                excludeTags: ["retired"],
                includeProtocols: [.RDP],
                scopeFolderId: wrapper.id
            )
        )
        let matches = PropertyInheritanceResolver.evaluateSmartFolder(smart, in: inNewFolder)
        XCTAssertEqual(matches.map { $0.name }, ["ZRH-DC01"])

        // 4. NexusErrorClassifier FreeRDP error diagnostics
        let rdpErr = NexusErrorClassifier.classifyRdpError(
            host: "10.41.0.10",
            port: 3389,
            rawNativeError: "[0x00020009] ERRCONNECT_AUTHENTICATION_FAILED",
            connectionId: "r-c1"
        )
        XCTAssertEqual(rdpErr.category, .authenticationFailed)
        XCTAssertEqual(rdpErr.recoveryConnectionId, "r-c1")

        let rdpInternal = NexusErrorClassifier.classifyRdpError(
            host: "10.2.200.27",
            port: 3389,
            rawNativeError: "INTERNAL: freerdp_context_new() initialization failed."
        )
        XCTAssertEqual(rdpInternal.category, .internal)

        let rdpCertChanged = NexusErrorClassifier.classifyRdpError(
            host: "10.2.200.27",
            port: 3389,
            rawNativeError: "TLS_CERTIFICATE_CHANGED: X.509 certificate fingerprint changed"
        )
        XCTAssertEqual(rdpCertChanged.category, .tlsCertificate)
    }

    func testInAppMacAutoUpdaterBundleExtractionTargetResolutionAndSwapScript() throws {
        // 1. Verify target install resolution keeps writable .app paths in place and redirects read-only /Volumes DMG mounts
        let appInApplications = URL(fileURLWithPath: "/Applications/NexusTS.app")
        XCTAssertEqual(
            MacGitHubReleaseUpdater.resolveTargetInstallAppURL(currentBundleURL: appInApplications, isApplicationsWritable: true).path,
            "/Applications/NexusTS.app"
        )
        let customDesktopApp = URL(fileURLWithPath: "/Users/tester/Desktop/NexusTS.app")
        XCTAssertEqual(
            MacGitHubReleaseUpdater.resolveTargetInstallAppURL(currentBundleURL: customDesktopApp, isApplicationsWritable: false).path,
            "/Users/tester/Desktop/NexusTS.app"
        )
        let mountedDmgApp = URL(fileURLWithPath: "/Volumes/NexusTS 0.3.0-beta.4/NexusTS.app")
        XCTAssertEqual(
            MacGitHubReleaseUpdater.resolveTargetInstallAppURL(currentBundleURL: mountedDmgApp, isApplicationsWritable: true).path,
            "/Applications/NexusTS.app"
        )
        XCTAssertTrue(
            MacGitHubReleaseUpdater.resolveTargetInstallAppURL(currentBundleURL: mountedDmgApp, isApplicationsWritable: false).path
                .hasSuffix("/Applications/NexusTS.app")
        )

        // 2. Verify atomic swap shell script escapes paths with spaces/apostrophes and relaunches without any hdiutil/DMG mounting
        let swapScript = MacGitHubReleaseUpdater.buildAtomicSwapShellScript(
            oldPid: 42424,
            stagedAppPath: "/var/folders/xx/NexusTS Stage/extracted/NexusTS.app",
            targetAppPath: "/Users/petros's mac/Applications/NexusTS.app",
            stagingRootPath: "/var/folders/xx/NexusTS Stage"
        )
        XCTAssertTrue(swapScript.contains("OLD_PID=42424"))
        XCTAssertTrue(swapScript.contains("/usr/bin/open -n"))
        XCTAssertTrue(swapScript.contains("/usr/bin/xattr -cr"))
        XCTAssertFalse(swapScript.contains("hdiutil"), "In-app updater must never mount or require a DMG")
        XCTAssertTrue(swapScript.contains("'\\''"), "Single quotes in paths must be safely POSIX-escaped")

        // 3. Verify real .zip app bundle creation, SHA-256 digest verification, and ditto extraction
        let tempRoot = FileManager.default.temporaryDirectory
            .appendingPathComponent("NexusTSUpdaterTest-\(UUID().uuidString.lowercased())", isDirectory: true)
        defer { try? FileManager.default.removeItem(at: tempRoot) }

        let mockApp = tempRoot.appendingPathComponent("source/NexusTS.app", isDirectory: true)
        let contentsDir = mockApp.appendingPathComponent("Contents", isDirectory: true)
        let macosDir = contentsDir.appendingPathComponent("MacOS", isDirectory: true)
        try FileManager.default.createDirectory(at: macosDir, withIntermediateDirectories: true)
        try Data("<?xml version=\"1.0\"?><plist version=\"1.0\"><dict/></plist>".utf8)
            .write(to: contentsDir.appendingPathComponent("Info.plist"))
        let execURL = macosDir.appendingPathComponent("NexusTS")
        try Data("#!/bin/sh\necho NEXUSTS_MOCK_OK\n".utf8).write(to: execURL)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: execURL.path)

        let zipURL = tempRoot.appendingPathComponent("NexusTS-v0.3.0-beta.4-macOS-Universal.zip")
        let dittoZip = Process()
        dittoZip.executableURL = URL(fileURLWithPath: "/usr/bin/ditto")
        dittoZip.arguments = ["-c", "-k", "--keepParent", mockApp.path, zipURL.path]
        try dittoZip.run()
        dittoZip.waitUntilExit()
        XCTAssertEqual(dittoZip.terminationStatus, 0)

        let zipData = try Data(contentsOf: zipURL)
        let digest = MacGitHubReleaseUpdater.sha256Hex(of: zipData)
        XCTAssertEqual(digest.count, 64)
        let manifest = "\(digest)  NexusTS-v0.3.0-beta.4-macOS-Universal.zip\n"
        XCTAssertTrue(
            MacGitHubReleaseUpdater.verifySha256Manifest(
                fileData: zipData,
                expectedAssetName: "NexusTS-v0.3.0-beta.4-macOS-Universal.zip",
                manifestText: manifest
            )
        )

        let extractDir = tempRoot.appendingPathComponent("unpacked", isDirectory: true)
        let unpackedApp = try MacGitHubReleaseUpdater.extractAndValidateAppBundle(
            fromZip: zipURL,
            into: extractDir
        )
        XCTAssertEqual(unpackedApp.lastPathComponent, "NexusTS.app")
        XCTAssertTrue(
            FileManager.default.isExecutableFile(
                atPath: unpackedApp.appendingPathComponent("Contents/MacOS/NexusTS").path
            )
        )
    }

    func testRdpMetalDirtyRegionAndRemoteCursorParityDefaults() {
        let defaultSettings = RdpProtocolSettings()
        XCTAssertFalse(defaultSettings.redirectSmartcardYubiKey)
        XCTAssertEqual(defaultSettings.resizeBehavior, .DYNAMIC_DISP_DVC)
        XCTAssertTrue(defaultSettings.hiDpiEnabled)
        XCTAssertEqual(defaultSettings.desktopScaleFactorPercent, 200)
        XCTAssertEqual(defaultSettings.deviceScaleFactorPercent, 180)
        XCTAssertFalse(defaultSettings.hiDpiScaleCustomized)

        // Retina Macs (backingScaleFactor = 2.0) negotiate crisp 200% HiDPI by default in TS_UD_CS_CORE
        let retinaSession = NativeRdpSession(
            host: "10.2.200.11",
            username: "admin",
            password: "",
            domain: "",
            settings: defaultSettings,
            initialWidth: 2368,
            initialHeight: 1624,
            backingScaleFactor: 2.0
        )
        XCTAssertTrue(retinaSession.initialHiDpiActive)
        XCTAssertEqual(retinaSession.initialScalePercent, 200)
        XCTAssertEqual(retinaSession.initialPixelWidth, 2368)
        XCTAssertEqual(retinaSession.initialPixelHeight, 1624)
        XCTAssertFalse(retinaSession.hasValidFrame)
        XCTAssertEqual(retinaSession.cursorSequence, 0)
        if case .systemDefault = retinaSession.copyRemoteCursor() {
            // Expected default OS cursor state prior to connection
        } else {
            XCTFail("Expected .systemDefault remote cursor before connect")
        }
        let consumed = retinaSession.consumeDirtyRegion(forceFull: false) { _ in }
        XCTAssertFalse(consumed)

        retinaSession.pushLocalClipboard("Hello from macOS Command+C")
        retinaSession.pushLocalClipboardFiles(["/tmp/example.txt"])
        XCTAssertEqual(retinaSession.remoteClipboardKind, .none)
        XCTAssertEqual(retinaSession.pullRemoteClipboard(), "")
        XCTAssertTrue(retinaSession.pullRemoteClipboardFiles().isEmpty)
        retinaSession.requestSynchronizedPaste()
        retinaSession.logOffRemoteSession()
        retinaSession.setFrameReadyHandler {}
        retinaSession.setFrameReadyHandler(nil)

        // Existing vault connections saved with legacy desktopScaleFactorPercent == 100 + hiDpiEnabled == true resolve to 200%
        var legacySavedSettings = RdpProtocolSettings()
        legacySavedSettings.desktopScaleFactorPercent = 100
        legacySavedSettings.hiDpiEnabled = true
        XCTAssertEqual(
            NativeRdpSession.resolveEffectiveHiDpiScalePercent(settings: legacySavedSettings, backingScaleFactor: 2.0),
            200
        )

        // Explicit 150% override is honored when selected
        var explicit150 = RdpProtocolSettings()
        explicit150.desktopScaleFactorPercent = 150
        explicit150.deviceScaleFactorPercent = 140
        explicit150.hiDpiScaleCustomized = true
        XCTAssertEqual(
            NativeRdpSession.resolveEffectiveHiDpiScalePercent(settings: explicit150, backingScaleFactor: 2.0),
            150
        )

        // >4K Retina viewports (e.g. 5K Studio Display 5120x2880) clamp proportionally to 3840x2160 when H.264/AVC444 is enabled
        let clamped5k = NativeRdpSession.clampSurfacePixelDimensions(width: 5120, height: 2880, h264Enabled: true)
        XCTAssertEqual(clamped5k.width, 3840)
        XCTAssertEqual(clamped5k.height, 2160)

        // 1x non-Retina displays keep 100% scale without forcing HiDPI
        let nonRetinaSession = NativeRdpSession(
            host: "10.2.200.11",
            username: "admin",
            password: "",
            domain: "",
            settings: defaultSettings,
            backingScaleFactor: 1.0
        )
        XCTAssertFalse(nonRetinaSession.initialHiDpiActive)
        XCTAssertEqual(nonRetinaSession.initialScalePercent, 100)

        // Disabling HiDPI forces 1x even on 2x Retina displays
        var oneXSettings = RdpProtocolSettings()
        oneXSettings.hiDpiEnabled = false
        let oneXSession = NativeRdpSession(
            host: "10.2.200.11",
            username: "admin",
            password: "",
            domain: "",
            settings: oneXSettings,
            backingScaleFactor: 2.0
        )
        XCTAssertFalse(oneXSession.initialHiDpiActive)
        XCTAssertEqual(oneXSession.initialScalePercent, 100)

        // Verify Mac Command (⌘C / ⌘V / ⌘X / ⌘A / ⌘Z / ⇧⌘Z / ⌘Y / ⌘F / ⌘S) -> Windows Ctrl scancode mapping
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x08, charactersIgnoringModifiers: "c", hasCommand: true),
            .copy
        )
        XCTAssertEqual(MacRdpScancodeMapper.MacCommandRemoteShortcut.copy.windowsCtrlScancode, 0x2E)
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x09, charactersIgnoringModifiers: "v", hasCommand: true),
            .paste
        )
        XCTAssertEqual(MacRdpScancodeMapper.MacCommandRemoteShortcut.paste.windowsCtrlScancode, 0x2F)
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x07, charactersIgnoringModifiers: "x", hasCommand: true),
            .cut
        )
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x00, charactersIgnoringModifiers: "a", hasCommand: true),
            .selectAll
        )
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x06, charactersIgnoringModifiers: "z", hasCommand: true),
            .undo
        )
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x06, charactersIgnoringModifiers: "Z", hasCommand: true, hasShift: true),
            .redo
        )
        // German QWERTZ layout: Z is on physical keyCode 0x10 with charactersIgnoringModifiers == "z"
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x10, charactersIgnoringModifiers: "z", hasCommand: true),
            .undo
        )
        // Greek / non-Latin layout fallback: physical keyCode 0x08 ('Ψ') still maps ⌘C -> Copy
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x08, charactersIgnoringModifiers: "ψ", hasCommand: true),
            .copy
        )
        // Plain ⌘F / ⌘S map to Ctrl+F / Ctrl+S, while ⇧⌘F, ⌃⌘S, and ⇧⌘S preserve NexusTS app shortcuts
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x03, charactersIgnoringModifiers: "f", hasCommand: true),
            .find
        )
        XCTAssertNil(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x03, charactersIgnoringModifiers: "F", hasCommand: true, hasShift: true)
        )
        XCTAssertEqual(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x01, charactersIgnoringModifiers: "s", hasCommand: true),
            .save
        )
        XCTAssertNil(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x01, charactersIgnoringModifiers: "s", hasCommand: true, hasControl: true)
        )
        XCTAssertNil(
            MacRdpScancodeMapper.mapMacCommandShortcut(keyCode: 0x01, charactersIgnoringModifiers: "S", hasCommand: true, hasShift: true)
        )
    }

    func testRdpAdaptiveGfxCodecProfileLegacyMigrationAnd5KRetinaViewport() throws {
        // Default profile uses sub-ms SIMD Adaptive RDPGFX 10.4 (Progressive / ClearCodec / Planar)
        let defaults = RdpProtocolSettings()
        XCTAssertTrue(defaults.enableGfxPipeline)
        XCTAssertEqual(defaults.gfxCodecProfile, .ADAPTIVE_PROGRESSIVE)
        XCTAssertEqual(defaults.gfxCodecProfile.nativeCode, 0)
        XCTAssertFalse(defaults.enableH264Avc444)
        XCTAssertFalse(NativeRdpSession.usesH264Cap(settings: defaults))

        // 5K Retina viewport (5120x2880) is preserved natively without 4K downscaling under Adaptive Progressive
        let native5k = NativeRdpSession.clampSurfacePixelDimensions(
            width: 5120,
            height: 2880,
            h264Enabled: NativeRdpSession.usesH264Cap(settings: defaults)
        )
        XCTAssertEqual(native5k.width, 5120)
        XCTAssertEqual(native5k.height, 2880)

        // Ultra-wide >8K viewport clamps proportionally to 8192 max Metal/RDPGFX edge
        let clamped10k = NativeRdpSession.clampSurfacePixelDimensions(
            width: 10240,
            height: 5760,
            h264Enabled: false
        )
        XCTAssertEqual(clamped10k.width, 8192)
        XCTAssertEqual(clamped10k.height, 4608)

        // H.264 profiles (AVC420_VIDEO & AVC444_CHROMA) enforce Level 5.1 3840x2160 cap
        var avc420 = RdpProtocolSettings()
        avc420.gfxCodecProfile = .AVC420_VIDEO
        XCTAssertEqual(avc420.gfxCodecProfile.nativeCode, 1)
        XCTAssertTrue(NativeRdpSession.usesH264Cap(settings: avc420))
        let clampedAvc420 = NativeRdpSession.clampSurfacePixelDimensions(
            width: 5120,
            height: 2880,
            h264Enabled: NativeRdpSession.usesH264Cap(settings: avc420)
        )
        XCTAssertEqual(clampedAvc420.width, 3840)
        XCTAssertEqual(clampedAvc420.height, 2160)

        var avc444 = RdpProtocolSettings()
        avc444.gfxCodecProfile = .AVC444_CHROMA
        avc444.enableH264Avc444 = true
        XCTAssertEqual(avc444.gfxCodecProfile.nativeCode, 2)
        XCTAssertTrue(NativeRdpSession.usesH264Cap(settings: avc444))

        // Pre-v0.8.5 saved vault JSON without gfxCodecProfile upgrades automatically to ADAPTIVE_PROGRESSIVE
        let legacyAvcTrueJson = Data(#"{"port":3389,"enableGfxPipeline":true,"enableH264Avc444":true}"#.utf8)
        let decodedLegacyAvc = try JSONDecoder().decode(RdpProtocolSettings.self, from: legacyAvcTrueJson)
        XCTAssertEqual(decodedLegacyAvc.gfxCodecProfile, .ADAPTIVE_PROGRESSIVE)
        XCTAssertFalse(decodedLegacyAvc.enableH264Avc444)

        let legacyAvcFalseJson = Data(#"{"port":3389,"enableGfxPipeline":true,"enableH264Avc444":false}"#.utf8)
        let decodedLegacyProg = try JSONDecoder().decode(RdpProtocolSettings.self, from: legacyAvcFalseJson)
        XCTAssertEqual(decodedLegacyProg.gfxCodecProfile, .ADAPTIVE_PROGRESSIVE)
        XCTAssertFalse(decodedLegacyProg.enableH264Avc444)

        // Round-trip all RdpGfxCodecProfile variants through JSON
        for profile in RdpGfxCodecProfile.allCases {
            var original = RdpProtocolSettings()
            original.gfxCodecProfile = profile
            original.enableH264Avc444 = (profile == .AVC444_CHROMA)
            let encoded = try JSONEncoder().encode(original)
            let decoded = try JSONDecoder().decode(RdpProtocolSettings.self, from: encoded)
            XCTAssertEqual(decoded.gfxCodecProfile, profile)
        }

        // Verify multi-rect dirty consumption, reconnect state, releaseAllKeys, and extended mouse buttons on idle session
        let session5k = NativeRdpSession(
            host: "10.2.200.11",
            username: "admin",
            password: "",
            domain: "",
            settings: defaults,
            initialWidth: 5120,
            initialHeight: 2880,
            backingScaleFactor: 2.0
        )
        XCTAssertEqual(session5k.initialPixelWidth, 5120)
        XCTAssertEqual(session5k.initialPixelHeight, 2880)
        XCTAssertFalse(session5k.isReconnecting)
        XCTAssertEqual(session5k.reconnectAttempt, 0)
        XCTAssertFalse(session5k.consumeDirtyRects(forceFull: false) { _ in })
        session5k.releaseAllKeys()
        session5k.sendExtendedPointerEvent(xflags: 0x8001, x: 120, y: 240)
        session5k.sendExtendedPointerEvent(xflags: 0x0001, x: 120, y: 240)
    }

    func testV086Retina200MigrationAndExplicitScalePreservation() throws {
        // Pre-v0.8.6 saved JSON with automatic 150% or 100% (no hiDpiScaleCustomized) migrates cleanly to 200% / 180
        let legacy150Json = Data(#"{"port":3389,"hiDpiEnabled":true,"desktopScaleFactorPercent":150,"deviceScaleFactorPercent":140}"#.utf8)
        let migrated150 = try JSONDecoder().decode(RdpProtocolSettings.self, from: legacy150Json)
        XCTAssertEqual(migrated150.desktopScaleFactorPercent, 200)
        XCTAssertEqual(migrated150.deviceScaleFactorPercent, 180)
        XCTAssertFalse(migrated150.hiDpiScaleCustomized)

        let legacy100Json = Data(#"{"port":3389,"hiDpiEnabled":true,"desktopScaleFactorPercent":100,"deviceScaleFactorPercent":100}"#.utf8)
        let migrated100 = try JSONDecoder().decode(RdpProtocolSettings.self, from: legacy100Json)
        XCTAssertEqual(migrated100.desktopScaleFactorPercent, 200)
        XCTAssertEqual(migrated100.deviceScaleFactorPercent, 180)
        XCTAssertFalse(migrated100.hiDpiScaleCustomized)

        // Explicit 150% user customization (hiDpiScaleCustomized == true) is preserved across JSON round-trip
        let explicit150Json = Data(#"{"port":3389,"hiDpiEnabled":true,"desktopScaleFactorPercent":150,"deviceScaleFactorPercent":140,"hiDpiScaleCustomized":true}"#.utf8)
        let preserved150 = try JSONDecoder().decode(RdpProtocolSettings.self, from: explicit150Json)
        XCTAssertEqual(preserved150.desktopScaleFactorPercent, 150)
        XCTAssertEqual(preserved150.deviceScaleFactorPercent, 140)
        XCTAssertTrue(preserved150.hiDpiScaleCustomized)

        let reencoded = try JSONEncoder().encode(preserved150)
        let roundTripped = try JSONDecoder().decode(RdpProtocolSettings.self, from: reencoded)
        XCTAssertEqual(roundTripped.desktopScaleFactorPercent, 150)
        XCTAssertEqual(roundTripped.deviceScaleFactorPercent, 140)
        XCTAssertTrue(roundTripped.hiDpiScaleCustomized)
    }

    func testV087HyperVMultiTransportParsersVmConnectPcbAndRoyalTsRoundTrip() throws {
        // 1. Legacy poisoned Proxmox (:8006) & bare HTTPS (:443) Hyper-V endpoints auto-sanitize to ""
        XCTAssertEqual(
            CloudHypervisorClient.sanitizedHyperVEndpoint(rawEndpoint: "https://localhost:8006", connectionHost: "hv01.corp.local"),
            ""
        )
        XCTAssertEqual(
            CloudHypervisorClient.sanitizedHyperVEndpoint(rawEndpoint: "https://hv01.corp.local", connectionHost: "hv01.corp.local"),
            ""
        )
        XCTAssertEqual(
            CloudHypervisorClient.sanitizedHyperVEndpoint(rawEndpoint: "http://hv01.corp.local:5985/wsman", connectionHost: "hv01.corp.local"),
            "http://hv01.corp.local:5985/wsman"
        )

        // 2. VMConnect Preconnection Blob (PCB v2) brace stripping, EnhancedMode=1 suffix, and TCP 2179 port selection
        let basicVmRdp = RdpProtocolSettings(
            hyperVVmId: "{A1B2C3D4-1111-2222-3333-444455556666}",
            hyperVEnhancedSession: false
        )
        XCTAssertEqual(basicVmRdp.effectivePreconnectionBlob, "A1B2C3D4-1111-2222-3333-444455556666")
        XCTAssertEqual(basicVmRdp.effectiveRdpPort, 2179)

        let enhancedVmRdp = RdpProtocolSettings(
            hyperVVmId: "a1b2c3d4-1111-2222-3333-444455556666",
            hyperVEnhancedSession: true
        )
        XCTAssertEqual(enhancedVmRdp.effectivePreconnectionBlob, "a1b2c3d4-1111-2222-3333-444455556666;EnhancedMode=1")
        XCTAssertEqual(enhancedVmRdp.effectiveRdpPort, 2179)

        // 3. Msvm_ComputerSystem WS-Man XML parser filters host computer and maps power states
        let wsmanXml = """
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:p="http://schemas.microsoft.com/wbem/wsman/1/wmi/root/virtualization/v2/Msvm_ComputerSystem">
          <s:Body>
            <p:Msvm_ComputerSystem>
              <p:Name>HV01</p:Name>
              <p:ElementName>HV01</p:ElementName>
              <p:Caption>Hosting Computer System</p:Caption>
              <p:EnabledState>2</p:EnabledState>
            </p:Msvm_ComputerSystem>
            <p:Msvm_ComputerSystem>
              <p:Name>11111111-2222-3333-4444-555555555555</p:Name>
              <p:ElementName>Prod-SQL-01</p:ElementName>
              <p:Caption>Virtual Machine</p:Caption>
              <p:EnabledState>2</p:EnabledState>
            </p:Msvm_ComputerSystem>
            <p:Msvm_ComputerSystem>
              <p:Name>66666666-7777-8888-9999-000000000000</p:Name>
              <p:ElementName>Lab-DC-02</p:ElementName>
              <p:Caption>Virtual Machine</p:Caption>
              <p:EnabledState>32769</p:EnabledState>
            </p:Msvm_ComputerSystem>
          </s:Body>
        </s:Envelope>
        """
        let wsmanItems = CloudHypervisorClient.parseHyperVWsmanXml(wsmanXml, hostLabel: "hv01.corp.local")
        XCTAssertEqual(wsmanItems.count, 2)
        XCTAssertEqual(wsmanItems[0].id, "11111111-2222-3333-4444-555555555555")
        XCTAssertEqual(wsmanItems[0].name, "Prod-SQL-01")
        XCTAssertEqual(wsmanItems[0].powerState, "RUNNING")
        XCTAssertEqual(wsmanItems[1].powerState, "SAVED")

        // 4. OpenSSH PowerShell Get-VM JSON parser handles both single object and array outputs
        let singlePsJson = #"{"Id":"aaaa1111-bbbb-cccc-dddd-eeeeffff0001","Name":"Single-VM","State":"Running","CpuCount":4,"MemAssignedMB":8192,"MemStartupMB":4096}"#
        let singleItems = try CloudHypervisorClient.parseHyperVPowerShellJson(singlePsJson, hostLabel: "hv01.corp.local")
        XCTAssertEqual(singleItems.count, 1)
        XCTAssertEqual(singleItems[0].name, "Single-VM")
        XCTAssertEqual(singleItems[0].powerState, "RUNNING")
        XCTAssertEqual(singleItems[0].cpuSummary, "4 vCPU")

        // 5. Royal TS Hyper-V Host (<RoyalHyperVConnection> & HyperVMode=1) and VMConnect (<RoyalRDSConnection> HyperVMode=2) roundtrip
        let rtsxSample = """
        <?xml version="1.0" encoding="utf-8"?>
        <RTSZDocument>
          <RoyalDocument>
            <ID>doc-hv</ID>
            <Name>HyperV Lab</Name>
            <RoyalHyperVConnection>
              <ID>hv-host-1</ID>
              <Name>HV-Cluster-01</Name>
              <URI>hv01.corp.local</URI>
            </RoyalHyperVConnection>
            <RoyalRDSConnection>
              <ID>hv-vm-1</ID>
              <Name>SQL-VM-Console</Name>
              <URI>hv01.corp.local</URI>
              <HyperVMode>2</HyperVMode>
              <HyperVInstance>{11111111-2222-3333-4444-555555555555}</HyperVInstance>
              <HyperVPort>2179</HyperVPort>
              <HyperVEnhancedSessionMode>true</HyperVEnhancedSessionMode>
            </RoyalRDSConnection>
          </RoyalDocument>
        </RTSZDocument>
        """
        let imported = try EnterpriseVaultFormatImporter.importRoyalTsXml(Data(rtsxSample.utf8), vaultName: "HyperV Lab")
        XCTAssertEqual(imported.document.connections.count, 2)
        let hvHost = try XCTUnwrap(imported.document.connections.first(where: { $0.id == "hv-host-1" }))
        XCTAssertEqual(hvHost.protocolType, .HYPER_V)
        if case .cloudVirtualization(let cloudCfg) = hvHost.settings {
            XCTAssertEqual(cloudCfg.apiEndpointUrl, "")
        } else {
            XCTFail("Expected cloudVirtualization settings for HYPER_V")
        }

        let vmConsole = try XCTUnwrap(imported.document.connections.first(where: { $0.id == "hv-vm-1" }))
        XCTAssertEqual(vmConsole.protocolType, .RDP)
        XCTAssertEqual(vmConsole.effectivePort, 2179)
        if case .rdp(let rdpCfg) = vmConsole.settings {
            XCTAssertEqual(rdpCfg.hyperVVmId, "11111111-2222-3333-4444-555555555555")
            XCTAssertTrue(rdpCfg.hyperVEnhancedSession)
        } else {
            XCTFail("Expected RDP settings for HyperVMode=2 VM console")
        }

        let exportedXml = VaultDocumentExporter.exportRoyalTsXmlString(document: imported.document)
        XCTAssertTrue(exportedXml.contains("<RoyalHyperVConnection>"))
        XCTAssertTrue(exportedXml.contains("<HyperVInstance>11111111-2222-3333-4444-555555555555</HyperVInstance>"))
        XCTAssertTrue(exportedXml.contains("<HyperVEnhancedSessionMode>True</HyperVEnhancedSessionMode>"))
    }

    func testV089FinderApfsFileReferenceClipboardExtractionDragPayloadAndShutdownAllSessions() throws {
        let baseTmp = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
        let tempDir = baseTmp.appendingPathComponent("nexusts-v089-clip-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let reportUrl = tempDir.appendingPathComponent("Quarterly Report.pdf")
        try Data("PDF-1.7-NexusTS-Payload".utf8).write(to: reportUrl)
        let subFolderUrl = tempDir.appendingPathComponent("Deploy_Scripts", isDirectory: true)
        try FileManager.default.createDirectory(at: subFolderUrl, withIntermediateDirectories: true)
        let nestedUrl = subFolderUrl.appendingPathComponent("bootstrap.ps1")
        try Data("Write-Host 'Ready'".utf8).write(to: nestedUrl)

        let expectedReportPath = reportUrl.resolvingSymlinksInPath().standardizedFileURL.path
        let expectedSubFolderPath = subFolderUrl.resolvingSymlinksInPath().standardizedFileURL.path

        // 1. Real APFS file-reference URL (file:///.file/id=...) via CFURLCreateFileReferenceURL
        //    + Finder .string bare basename on NSPasteboard
        let pb = NSPasteboard.withUniqueName()
        defer { pb.releaseGlobally() }

        let rawRefString: String
        if let cfRef = CFURLCreateFileReferenceURL(kCFAllocatorDefault, reportUrl as CFURL, nil)?.takeRetainedValue() {
            rawRefString = CFURLGetString(cfRef) as String
        } else {
            rawRefString = reportUrl.absoluteString
        }
        if let syntheticRefUrl = URL(string: rawRefString) {
            let resolvedFromRef = MacPasteboardClipboardExtractor.resolveLocalFilePath(syntheticRefUrl)
            XCTAssertEqual(resolvedFromRef?.resolvingSymlinksIfPossible(), expectedReportPath)
        }
        if let bridgedRefUrl = (reportUrl as NSURL).fileReferenceURL() {
            let resolvedFromBridged = MacPasteboardClipboardExtractor.resolveLocalFilePath(bridgedRefUrl)
            XCTAssertEqual(resolvedFromBridged?.resolvingSymlinksIfPossible(), expectedReportPath)
        }

        pb.clearContents()
        let item = NSPasteboardItem()
        item.setString(rawRefString, forType: .fileURL)
        item.setString("Quarterly Report.pdf", forType: .string)
        XCTAssertTrue(pb.writeObjects([item]))

        let extracted = MacPasteboardClipboardExtractor.extractClipboardPayload(from: pb)
        if case .files(let paths) = extracted {
            XCTAssertEqual(paths.count, 1)
            XCTAssertEqual(paths[0].resolvingSymlinksIfPossible(), expectedReportPath)
        } else {
            XCTFail("Expected .files payload from APFS file reference URL (\(rawRefString)), got \(extracted)")
        }

        // 2. Multi-selection (file + directory) & duplicate deduplication
        pb.clearContents()
        let multiUrls: [NSURL] = [reportUrl as NSURL, subFolderUrl as NSURL, reportUrl as NSURL]
        XCTAssertTrue(pb.writeObjects(multiUrls))
        if case .files(let multiPaths) = MacPasteboardClipboardExtractor.extractClipboardPayload(from: pb) {
            let normalized = multiPaths.map { $0.resolvingSymlinksIfPossible() }
            XCTAssertEqual(normalized, [expectedReportPath, expectedSubFolderPath])
        } else {
            XCTFail("Expected .files([report, subFolder])")
        }

        // 3. Deleted file URL + Finder .string basename MUST return .fileIntentEmpty (never .text("deleted.zip")!)
        let deletedUrl = tempDir.appendingPathComponent("deleted.zip")
        pb.clearContents()
        let staleItem = NSPasteboardItem()
        staleItem.setString(deletedUrl.absoluteString, forType: .fileURL)
        staleItem.setString("deleted.zip", forType: .string)
        XCTAssertTrue(pb.writeObjects([staleItem]))
        XCTAssertEqual(MacPasteboardClipboardExtractor.extractClipboardPayload(from: pb), .fileIntentEmpty)

        // 4. Plain text pasteboard (without .fileURL) extracts .text cleanly
        pb.clearContents()
        pb.setString("Get-VM | Select-Object Name,State", forType: .string)
        XCTAssertEqual(
            MacPasteboardClipboardExtractor.extractClipboardPayload(from: pb),
            .text("Get-VM | Select-Object Name,State")
        )

        // 5. NativeRdpSession file clipboard staging & race-free shutdownAllNativeSessions() idempotency
        let s1 = NativeRdpSession(
            host: "10.2.200.21",
            username: "Administrator",
            password: "",
            domain: "",
            settings: RdpProtocolSettings(),
            initialWidth: 1920,
            initialHeight: 1080,
            backingScaleFactor: 2.0
        )
        let s2 = NativeRdpSession(
            host: "10.2.200.22",
            username: "Administrator",
            password: "",
            domain: "",
            settings: RdpProtocolSettings(hyperVVmId: "11111111-2222-3333-4444-555555555555"),
            initialWidth: 1920,
            initialHeight: 1080,
            backingScaleFactor: 2.0
        )
        s1.pushLocalClipboardFiles([expectedReportPath, expectedSubFolderPath])
        s2.pushLocalClipboardFiles([expectedReportPath])
        NativeRdpSession.shutdownAllNativeSessions()
        NativeRdpSession.shutdownAllNativeSessions()
        s1.destroy()
        s2.destroy()
    }

    func testV0810CliprdrRawBinaryTxtAndExeRoundTripAndExactFilenames() throws {
        let baseTmp = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
        let tempDir = baseTmp.appendingPathComponent("nexusts-v0810-bin-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        // 1. Run the native C++20 CLIPRDR binary round-trip suite (.txt with LF/CRLF/BOM/NUL,
        //    0-byte .txt, >8 MiB .exe PE binary, virtual stream FILECONTENTS_SIZE, ANSI FILEDESCRIPTORA)
        XCTAssertTrue(
            NativeRdpSession.runNativeCliprdrBinaryRoundTripSelfTest(workDirectory: tempDir.path),
            "Native CLIPRDR binary round-trip self-test failed"
        )

        // 2. Verify NSPasteboard extraction preserves exact .txt, .exe, and symlink filenames without mutation
        let txtFile = tempDir.appendingPathComponent("server_config.txt")
        let exeFile = tempDir.appendingPathComponent("NexusInstaller_x64.exe")
        let targetBin = tempDir.appendingPathComponent("internal_payload_v2.bin")
        let symlinkExe = tempDir.appendingPathComponent("LaunchAgent.exe")

        try Data([0xEF, 0xBB, 0xBF, 0x41, 0x0A, 0x42, 0x0D, 0x0A, 0x00, 0xFF]).write(to: txtFile)
        try Data([0x4D, 0x5A, 0x90, 0x00, 0x03, 0x00, 0x1A, 0x00, 0xFF]).write(to: exeFile)
        try Data([0x4D, 0x5A, 0x50, 0x45, 0x00, 0x00]).write(to: targetBin)
        try FileManager.default.createSymbolicLink(at: symlinkExe, withDestinationURL: targetBin)

        let pb = NSPasteboard.withUniqueName()
        defer { pb.releaseGlobally() }

        let txtRefStr = (CFURLCreateFileReferenceURL(kCFAllocatorDefault, txtFile as CFURL, nil)?.takeRetainedValue()
            .flatMap { CFURLGetString($0) as String? }) ?? txtFile.absoluteString
        let exeRefStr = (CFURLCreateFileReferenceURL(kCFAllocatorDefault, exeFile as CFURL, nil)?.takeRetainedValue()
            .flatMap { CFURLGetString($0) as String? }) ?? exeFile.absoluteString

        pb.clearContents()
        let i1 = NSPasteboardItem()
        i1.setString(txtRefStr, forType: .fileURL)
        i1.setString("server_config.txt", forType: .string)
        let i2 = NSPasteboardItem()
        i2.setString(exeRefStr, forType: .fileURL)
        i2.setString("NexusInstaller_x64.exe", forType: .string)
        let i3 = NSPasteboardItem()
        i3.setString(symlinkExe.absoluteString, forType: .fileURL)
        i3.setString("LaunchAgent.exe", forType: .string)
        XCTAssertTrue(pb.writeObjects([i1, i2, i3]))

        if case .files(let extractedPaths) = MacPasteboardClipboardExtractor.extractClipboardPayload(from: pb) {
            let names = extractedPaths.map { URL(fileURLWithPath: $0).lastPathComponent }
            XCTAssertEqual(names, ["server_config.txt", "NexusInstaller_x64.exe", "LaunchAgent.exe"])
        } else {
            XCTFail("Expected .files([server_config.txt, NexusInstaller_x64.exe, LaunchAgent.exe])")
        }
    }
}

private extension String {
    func resolvingSymlinksIfPossible() -> String {
        URL(fileURLWithPath: self).resolvingSymlinksInPath().standardizedFileURL.path
    }
}



