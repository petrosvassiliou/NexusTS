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
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("1.1.8", isNewerThan: "1.1.7"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("1.1.9", isNewerThan: "1.1.8"))
        XCTAssertTrue(MacGitHubReleaseUpdater.compareSemver("1.2.0", isNewerThan: "1.1.9"))
        XCTAssertFalse(MacGitHubReleaseUpdater.compareSemver("1.2.0", isNewerThan: "1.2.0"))
    }

    func testV118LocalizationUpgradeVersionAndVaultReplacementCompatibility() throws {
        XCTAssertEqual(MacGitHubReleaseUpdater.currentVersion, "1.2.0")

        for lang in NexusAppLanguage.allCases {
            let catalog = LocalizedWorkspaceStrings.catalog(for: lang)
            XCTAssertFalse(catalog.studios.isEmpty)
            XCTAssertFalse(catalog.renameFolder.isEmpty)
            XCTAssertFalse(catalog.startAllRdp.isEmpty)
            XCTAssertFalse(catalog.deleteFolderAndContents.isEmpty)
            XCTAssertFalse(catalog.deleteFolderKeepContents.isEmpty)
            XCTAssertFalse(catalog.royalImportTitle.isEmpty)
            XCTAssertFalse(catalog.resetDatabase.isEmpty)
            XCTAssertFalse(catalog.systemCredentialsFolder.isEmpty)
            XCTAssertFalse(catalog.newSmartFolder.isEmpty)
            XCTAssertFalse(catalog.editConnection.isEmpty)
        }

        // Verify a persisted v1.1.7 .nxts v2 vault with nested folders + RDP/SSH hosts upgrades seamlessly in v1.2.0
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
    }
}
