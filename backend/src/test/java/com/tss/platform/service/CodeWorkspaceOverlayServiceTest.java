package com.tss.platform.service;

import com.tss.platform.entity.CodeAsset;
import com.tss.platform.entity.CodeVersion;
import com.tss.platform.entity.CodeWorkspace;
import com.tss.platform.entity.CodeWorkspaceFileDelta;
import com.tss.platform.repository.CodeAssetRepository;
import com.tss.platform.repository.CodeVersionRepository;
import com.tss.platform.repository.CodeWorkspaceFileDeltaRepository;
import com.tss.platform.repository.CodeWorkspaceRepository;
import com.tss.platform.security.AuthContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CodeWorkspaceOverlayServiceTest {

    private final CodeWorkspaceRepository workspaceRepository = mock(CodeWorkspaceRepository.class);
    private final CodeWorkspaceFileDeltaRepository deltaRepository =
            mock(CodeWorkspaceFileDeltaRepository.class);
    private final CodeAssetRepository assetRepository = mock(CodeAssetRepository.class);
    private final CodeVersionRepository versionRepository = mock(CodeVersionRepository.class);
    private final CodeVersionArchiveReader archiveReader = mock(CodeVersionArchiveReader.class);
    private final CodeAssetAuditService auditService = mock(CodeAssetAuditService.class);
    private final AuthContext authContext = mock(AuthContext.class);

    private CodeWorkspaceOverlayService service;
    private CodeWorkspace workspace;

    @BeforeEach
    void setUp() {
        service = new CodeWorkspaceOverlayService(
                workspaceRepository,
                deltaRepository,
                assetRepository,
                versionRepository,
                archiveReader,
                new CodePathPolicy(),
                new CodeFilePolicy(),
                auditService,
                authContext
        );
        workspace = workspace("workspace-1", null, CodeWorkspace.STATUS_OPEN, 0L);
        stubReadable(workspace);
        when(workspaceRepository.findByIdAndDeletedFalseForUpdate("workspace-1"))
                .thenReturn(Optional.of(workspace));
        when(workspaceRepository.findAssetIdByIdAndDeletedFalse("workspace-1"))
                .thenReturn(Optional.of("asset-1"));
        when(workspaceRepository.findRevisionByIdAndDeletedFalse("workspace-1"))
                .thenAnswer(invocation -> Optional.ofNullable(workspace.getRevision()));
        when(assetRepository.findByIdAndDeletedFalseForUpdate("asset-1"))
                .thenReturn(Optional.of(asset()));
        when(workspaceRepository.save(any(CodeWorkspace.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(deltaRepository.save(any(CodeWorkspaceFileDelta.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(authContext.canAccessObjectName(
                "users/7/codes/asset-1/base.zip",
                7
        )).thenReturn(true);
    }

    @Test
    void rootTreeReturnsOnlyDirectChildrenDirectoryFirstWithoutReadingBodies() {
        CodeVersion base = attachBase();
        when(archiveReader.list(base, 7)).thenReturn(List.of(
                entry("z.py", 10),
                entry("dir/b.py", 11),
                entry("dir/sub/c.md", 12),
                entry("a.md", 13)
        ));
        byte[] updatedBytes = "updated\n".getBytes(StandardCharsets.UTF_8);
        CodeWorkspaceFileDeltaRepository.DeltaMetadata override = metadataOnlyDelta(
                "z.py",
                updatedBytes.length,
                new CodeFilePolicy().sha256(updatedBytes)
        );
        CodeWorkspaceFileDeltaRepository.DeltaMetadata deleted = metadata(deleteDelta("a.md"));
        when(deltaRepository.findMetadataByWorkspaceIdAndPathStartingWithOrderByPathAsc(
                "workspace-1", ""))
                .thenReturn(List.of(deleted, override));

        List<CodeWorkspaceTreeNode> nodes = service.tree("workspace-1", "/");

        assertEquals(List.of("dir", "z.py"), nodes.stream().map(CodeWorkspaceTreeNode::path).toList());
        assertEquals("DIRECTORY", nodes.get(0).nodeType());
        assertEquals("python", nodes.get(1).languageId());
        assertEquals((long) "updated\n".getBytes(StandardCharsets.UTF_8).length,
                nodes.get(1).sizeBytes());
        verify(archiveReader, never()).read(any(), any(), any(), anyLong());
    }

    @Test
    void nestedTreeNormalizesPrefixAndReturnsStableDirectChildren() {
        CodeVersion base = attachBase();
        when(archiveReader.list(base, 7)).thenReturn(List.of(
                entry("dir/z.py", 10),
                entry("dir/sub/c.md", 12),
                entry("dir/a.txt", 13),
                entry("other.py", 14)
        ));
        when(deltaRepository.findMetadataByWorkspaceIdAndPathStartingWithOrderByPathAsc(
                "workspace-1", "dir/"))
                .thenReturn(List.of());

        List<CodeWorkspaceTreeNode> nodes = service.tree("workspace-1", "dir");

        assertEquals(List.of("dir/sub", "dir/a.txt", "dir/z.py"),
                nodes.stream().map(CodeWorkspaceTreeNode::path).toList());
        assertEquals("DIRECTORY", nodes.get(0).nodeType());
        verify(archiveReader, never()).read(any(), any(), any(), anyLong());
    }

    @Test
    void nestedTreeEscapesSqlLikeMetacharactersInLiteralPrefix() {
        when(deltaRepository.findMetadataByWorkspaceIdAndPathStartingWithOrderByPathAsc(
                "workspace-1", "a!_!%/"))
                .thenReturn(List.of());

        assertEquals(List.of(), service.tree("workspace-1", "a_%"));

        verify(deltaRepository).findMetadataByWorkspaceIdAndPathStartingWithOrderByPathAsc(
                "workspace-1", "a!_!%/"
        );
    }

    @Test
    void contentReadsExactBaseBytesAndClosedWorkspaceIsReadOnly() {
        byte[] raw = new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, 'a', '\r', '\n'};
        CodeVersion base = attachBase();
        CodeArchiveEntry entry = entry("notes.txt", raw.length);
        when(archiveReader.list(base, 7)).thenReturn(List.of(entry));
        when(archiveReader.read(base, 7, entry, CodeFilePolicy.EDITABLE_LIMIT_BYTES))
                .thenReturn(raw.clone());
        workspace.setStatus(CodeWorkspace.STATUS_PUBLISHED);

        CodeWorkspaceContent content = service.content("workspace-1", "notes.txt");

        assertArrayEquals(raw, content.rawBytes());
        assertEquals("\ufeffa\r\n", content.content());
        assertEquals("UTF-8", content.charset());
        assertTrue(content.readOnly());
        raw[3] = 'x';
        assertEquals('a', content.rawBytes()[3]);
    }

    @Test
    void deltaOverrideWinsAndDeleteTombstoneIsGenericMissing() {
        CodeVersion base = attachBase();
        CodeArchiveEntry entry = entry("train.py", 100);
        when(archiveReader.list(base, 7)).thenReturn(List.of(entry));
        CodeWorkspaceFileDelta override = upsertDelta("train.py", "print('delta')\n");
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "train.py"))
                .thenReturn(Optional.of(override));

        CodeWorkspaceContent content = service.content("workspace-1", "train.py");

        assertEquals("print('delta')\n", content.content());
        verify(archiveReader, never()).read(any(), any(), any(), anyLong());

        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "train.py"))
                .thenReturn(Optional.of(deleteDelta("train.py")));
        assertThrows(
                CodeAssetAccessException.class,
                () -> service.content("workspace-1", "train.py")
        );
    }

    @Test
    void contentRejectsMixedHashAndRevisionSnapshot() {
        CodeWorkspaceFileDelta current = upsertDelta("notes.txt", "new state\n");
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "notes.txt"))
                .thenReturn(Optional.of(current));
        when(workspaceRepository.findRevisionByIdAndDeletedFalse("workspace-1"))
                .thenReturn(Optional.of(1L));

        CodeWorkspaceConflictException conflict = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.content("workspace-1", "notes.txt")
        );

        assertEquals("WORKSPACE_REVISION_CONFLICT", conflict.getReasonCode());
    }

    @Test
    void createAndUpdateUseRawHashCasAndIncrementOnce() {
        byte[] createdBytes = "print('one')\n".getBytes(StandardCharsets.UTF_8);
        when(deltaRepository.findMetadataByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of());

        CodeWorkspaceContent created = service.upsert(
                "workspace-1", "src/train.py", createdBytes, 0L, null
        );

        assertEquals(1L, workspace.getRevision());
        assertEquals(1L, created.workspaceRevision());
        assertArrayEquals(createdBytes, created.rawBytes());
        var deltaCaptor = org.mockito.ArgumentCaptor.forClass(CodeWorkspaceFileDelta.class);
        verify(deltaRepository).save(deltaCaptor.capture());
        CodeWorkspaceFileDelta saved = deltaCaptor.getValue();
        assertEquals("UPSERT", saved.getOperation());
        assertEquals(created.contentHash(), saved.getContentHash());
        createdBytes[0] = 'X';
        assertEquals('p', saved.getContentBytes()[0]);
        verify(auditService).fileUpserted(
                "asset-1", "workspace-1", 1L, null, created.contentHash()
        );

        byte[] updatedBytes = "print('two')\n".getBytes(StandardCharsets.UTF_8);
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "src/train.py"))
                .thenReturn(Optional.of(saved));
        when(deltaRepository.findMetadataByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(metadata(saved)));

        CodeWorkspaceContent updated = service.upsert(
                "workspace-1", "src/train.py", updatedBytes, 1L, created.contentHash()
        );

        assertEquals(2L, workspace.getRevision());
        assertEquals("print('two')\n", updated.content());
        verify(workspaceRepository, times(2)).save(workspace);
    }

    @Test
    void writesUseGlobalAssetThenWorkspaceLockOrder() {
        when(deltaRepository.findMetadataByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of());

        service.upsert("workspace-1", "notes.txt", bytes("ok\n"), 0L, null);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(
                workspaceRepository,
                assetRepository
        );
        order.verify(workspaceRepository).findAssetIdByIdAndDeletedFalse("workspace-1");
        order.verify(assetRepository).findByIdAndDeletedFalseForUpdate("asset-1");
        order.verify(workspaceRepository).findByIdAndDeletedFalseForUpdate("workspace-1");
    }

    @Test
    void staleRevisionWinsBeforeHashLookupAndFailuresDoNotMutate() {
        workspace.setRevision(4L);

        CodeWorkspaceConflictException stale = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.upsert(
                        "workspace-1",
                        "train.py",
                        "new".getBytes(StandardCharsets.UTF_8),
                        3L,
                        "f".repeat(64)
                )
        );

        assertEquals("WORKSPACE_REVISION_CONFLICT", stale.getReasonCode());
        assertEquals(4L, workspace.getRevision());
        verify(deltaRepository, never()).findByWorkspaceIdAndPath(any(), any());
        verify(deltaRepository, never()).save(any());
        verify(auditService, never()).fileUpserted(any(), any(), anyLong(), any(), any());
    }

    @Test
    void baseObjectAuthorizationPrecedesWorkspaceStatusAndRevisionDisclosure() {
        attachBase();
        workspace.setRevision(4L);
        when(authContext.canAccessObjectName(
                "users/7/codes/asset-1/base.zip",
                7
        )).thenReturn(false);

        assertThrows(
                CodeAssetAccessException.class,
                () -> service.upsert("workspace-1", "a.txt", bytes("new"), 3L, null)
        );

        verify(deltaRepository, never()).findByWorkspaceIdAndPath(any(), any());
        verify(archiveReader, never()).list(any(), any());
    }

    @Test
    void hashMismatchCreateMismatchAndTargetExistsAreConflictsWithoutWrites() {
        CodeWorkspaceFileDelta current = upsertDelta("a.txt", "old");
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "a.txt"))
                .thenReturn(Optional.of(current));

        CodeWorkspaceConflictException update = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.upsert(
                        "workspace-1", "a.txt", bytes("new"), 0L, "0".repeat(64)
                )
        );
        assertEquals("CONTENT_HASH_CONFLICT", update.getReasonCode());

        CodeWorkspaceConflictException create = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.upsert("workspace-1", "a.txt", bytes("new"), 0L, null)
        );
        assertEquals("CONTENT_HASH_CONFLICT", create.getReasonCode());

        CodeWorkspaceFileDelta targetDelta = upsertDelta("b.txt", "target");
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "b.txt"))
                .thenReturn(Optional.of(targetDelta));
        when(deltaRepository.findMetadataByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(metadata(current), metadata(targetDelta)));
        CodeWorkspaceConflictException target = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.move(
                        "workspace-1", "a.txt", "b.txt", 0L, current.getContentHash()
                )
        );
        assertEquals("TARGET_EXISTS", target.getReasonCode());
        assertEquals(0L, workspace.getRevision());
        verify(deltaRepository, never()).save(any());
    }

    @Test
    void moveWritesTargetAndSourceTombstoneThenIncrementsOnce() {
        CodeWorkspaceFileDelta source = upsertDelta("old/train.py", "print('ok')\n");
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "old/train.py"))
                .thenReturn(Optional.of(source));
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "new/train.py"))
                .thenReturn(Optional.empty());
        when(deltaRepository.findMetadataByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(metadata(source)));

        CodeWorkspaceContent moved = service.move(
                "workspace-1",
                "old/train.py",
                "new/train.py",
                0L,
                source.getContentHash()
        );

        assertEquals(1L, workspace.getRevision());
        assertEquals("new/train.py", moved.descriptor().path());
        assertEquals("DELETE", source.getOperation());
        assertEquals(null, source.getContentBytes());
        var captor = org.mockito.ArgumentCaptor.forClass(CodeWorkspaceFileDelta.class);
        verify(deltaRepository, times(2)).save(captor.capture());
        CodeWorkspaceFileDelta target = captor.getAllValues().stream()
                .filter(delta -> "new/train.py".equals(delta.getPath()))
                .findFirst()
                .orElseThrow();
        assertEquals("UPSERT", target.getOperation());
        assertArrayEquals(bytes("print('ok')\n"), target.getContentBytes());
        verify(auditService).fileMoved(
                "asset-1", "workspace-1", 1L, moved.contentHash()
        );
    }

    @Test
    void deleteKeepsExplicitTombstoneForDeltaOnlyFile() {
        CodeWorkspaceFileDelta source = upsertDelta("notes.txt", "draft\n");
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "notes.txt"))
                .thenReturn(Optional.of(source));

        long revision = service.delete(
                "workspace-1", "notes.txt", 0L, source.getContentHash()
        );

        assertEquals(1L, revision);
        assertEquals("DELETE", source.getOperation());
        assertEquals(null, source.getContentBytes());
        assertEquals(null, source.getContentHash());
        assertEquals(null, source.getSizeBytes());
        verify(deltaRepository).save(source);
        verify(auditService).fileDeleted(
                "asset-1", "workspace-1", 1L,
                new CodeFilePolicy().sha256(bytes("draft\n"))
        );

        assertThrows(
                CodeAssetAccessException.class,
                () -> service.delete("workspace-1", "notes.txt", 1L, null)
        );
    }

    @Test
    void enforcesOneMiBBoundaryStrictUtf8PathLengthAndTreeConflicts() {
        byte[] exactLimit = new byte[(int) CodeFilePolicy.EDITABLE_LIMIT_BYTES];
        java.util.Arrays.fill(exactLimit, (byte) 'a');
        when(deltaRepository.findMetadataByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of());

        CodeWorkspaceContent exact = service.upsert(
                "workspace-1", "large.txt", exactLimit, 0L, null
        );
        assertEquals(CodeFilePolicy.EDITABLE_LIMIT_BYTES, exact.descriptor().sizeBytes());

        byte[] tooLarge = new byte[(int) CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1];
        assertThrows(
                CodeContentTooLargeException.class,
                () -> service.upsert("workspace-1", "too-large.txt", tooLarge, 1L, null)
        );
        assertEquals(1L, workspace.getRevision());

        assertThrows(
                CodeValidationException.class,
                () -> service.upsert(
                        "workspace-1", "bad.txt", new byte[]{(byte) 0xc3, 0x28}, 1L, null
                )
        );
        assertEquals(1L, workspace.getRevision());

        String longPath = "a".repeat(1020) + ".txt";
        assertEquals(1024, longPath.length());
        service.upsert("workspace-1", longPath, bytes("ok"), 1L, null);
        assertThrows(
                CodeValidationException.class,
                () -> service.upsert(
                        "workspace-1", "a".repeat(1021) + ".txt", bytes("no"), 2L, null
                )
        );

        CodeWorkspaceFileDelta parentFile = upsertDelta("src", "plain");
        when(deltaRepository.findMetadataByWorkspaceIdOrderByPathAsc("workspace-1"))
                .thenReturn(List.of(metadata(parentFile)));
        assertThrows(
                CodeValidationException.class,
                () -> service.upsert("workspace-1", "src/train.py", bytes("x"), 2L, null)
        );
    }

    @Test
    void largeBaseFileIsListableDownloadableAndDeletableByStreamedHashCas() {
        CodeVersion base = attachBase();
        CodeArchiveEntry large = entry(
                "large.txt", CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1
        );
        when(archiveReader.list(base, 7)).thenReturn(List.of(large));
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "large.txt"))
                .thenReturn(Optional.empty());

        List<CodeWorkspaceTreeNode> tree = service.tree("workspace-1", null);
        assertEquals(1, tree.size());
        assertFalse(tree.get(0).previewable());
        assertTrue(tree.get(0).downloadable());

        assertThrows(CodeContentTooLargeException.class,
                () -> service.content("workspace-1", "large.txt"));
        assertThrows(CodeContentTooLargeException.class,
                () -> service.move("workspace-1", "large.txt", "moved.txt", 0L, "a".repeat(64)));
        String rawHash = "b".repeat(64);
        when(archiveReader.sha256(base, 7, large)).thenReturn(rawHash);

        CodeWorkspaceFileMetadata metadata = service.metadata(
                "workspace-1", "large.txt"
        );

        assertEquals(rawHash, metadata.contentHash());
        assertEquals(CodeFilePolicy.EDITABLE_LIMIT_BYTES + 1,
                metadata.descriptor().sizeBytes());
        assertFalse(metadata.descriptor().previewable());
        assertFalse(metadata.readOnly());

        long revision = service.delete(
                "workspace-1", "large.txt", 0L, rawHash
        );
        assertEquals(1L, revision);
        var deleteCaptor = org.mockito.ArgumentCaptor.forClass(CodeWorkspaceFileDelta.class);
        verify(deltaRepository).save(deleteCaptor.capture());
        assertEquals("DELETE", deleteCaptor.getValue().getOperation());
        assertEquals("large.txt", deleteCaptor.getValue().getPath());
        verify(auditService).fileDeleted(
                "asset-1", "workspace-1", 1L, rawHash
        );
        byte[] downloadable = bytes("large but bounded");
        when(archiveReader.read(
                base,
                7,
                large,
                CodeVersionArchiveReader.MAX_CODE_UNCOMPRESSED_BYTES
        )).thenReturn(downloadable);

        CodeWorkspaceDownload download = service.download("workspace-1", "large.txt");

        assertArrayEquals(downloadable, download.bytes());
        assertEquals("large.txt", download.descriptor().name());
        verify(archiveReader, never()).read(
                base, 7, large, CodeFilePolicy.EDITABLE_LIMIT_BYTES
        );
    }

    @Test
    void downloadCachesOneEffectiveByteCopyForDescriptorAndPayload() {
        byte[] original = bytes("original");
        CodeWorkspaceFileDelta delta = upsertDelta("once.txt", "original");
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "once.txt"))
                .thenReturn(Optional.of(delta));
        CodeFilePolicy observingPolicy = mock(CodeFilePolicy.class);
        when(observingPolicy.sha256(any(byte[].class))).thenReturn(delta.getContentHash());
        CodeFileDescriptor descriptor = new CodeFilePolicy().describe("once.txt", original);
        doAnswer(invocation -> {
            byte[] observed = invocation.getArgument(1);
            observed[0] = (byte) 'X';
            return descriptor;
        }).when(observingPolicy).describe(eq("once.txt"), any(byte[].class));
        CodeWorkspaceOverlayService singleReadService = new CodeWorkspaceOverlayService(
                workspaceRepository,
                deltaRepository,
                assetRepository,
                versionRepository,
                archiveReader,
                new CodePathPolicy(),
                observingPolicy,
                auditService,
                authContext
        );

        CodeWorkspaceDownload download = singleReadService.download(
                "workspace-1", "once.txt"
        );

        assertEquals((byte) 'X', download.bytes()[0]);
    }

    @Test
    void exactOneMiBBaseFileRemainsPreviewable() {
        CodeVersion base = attachBase();
        CodeArchiveEntry boundary = entry(
                "boundary.txt", CodeFilePolicy.EDITABLE_LIMIT_BYTES
        );
        byte[] bytes = new byte[(int) CodeFilePolicy.EDITABLE_LIMIT_BYTES];
        when(archiveReader.list(base, 7)).thenReturn(List.of(boundary));
        when(deltaRepository.findByWorkspaceIdAndPath("workspace-1", "boundary.txt"))
                .thenReturn(Optional.empty());
        when(archiveReader.read(
                base, 7, boundary, CodeFilePolicy.EDITABLE_LIMIT_BYTES
        )).thenReturn(bytes);

        CodeWorkspaceContent content = service.content("workspace-1", "boundary.txt");

        assertEquals(CodeFilePolicy.EDITABLE_LIMIT_BYTES, content.descriptor().sizeBytes());
        assertTrue(content.descriptor().previewable());
        assertTrue(content.descriptor().editable());
        assertEquals(1_048_576, content.rawBytes().length);
    }

    @Test
    void hidesCrossOwnerAndMakesClosedWorkspaceReadOnlyAfterAuthorization() {
        when(authContext.canAccessOwner(7)).thenReturn(false);
        assertThrows(CodeAssetAccessException.class,
                () -> service.content("workspace-1", "a.txt"));

        when(authContext.canAccessOwner(7)).thenReturn(true);
        workspace.setStatus(CodeWorkspace.STATUS_ABANDONED);
        CodeWorkspaceConflictException readOnly = assertThrows(
                CodeWorkspaceConflictException.class,
                () -> service.upsert("workspace-1", "a.txt", bytes("x"), 0L, null)
        );
        assertEquals("WORKSPACE_READ_ONLY", readOnly.getReasonCode());
        verify(deltaRepository, never()).save(any());
    }

    private void stubReadable(CodeWorkspace value) {
        when(workspaceRepository.findByIdAndDeletedFalse("workspace-1"))
                .thenReturn(Optional.of(value));
        when(assetRepository.findByIdAndDeletedFalse("asset-1"))
                .thenReturn(Optional.of(asset()));
        when(authContext.canAccessOwner(7)).thenReturn(true);
    }

    private CodeVersion attachBase() {
        workspace.setBaseVersionId("version-1");
        CodeVersion version = new CodeVersion();
        version.setId("version-1");
        version.setAssetId("asset-1");
        version.setOwnerUserId(7);
        version.setStatus("READY");
        version.setValidationStatus("PASSED");
        version.setArtifactSha256("a".repeat(64));
        version.setStoragePath("users/7/codes/asset-1/base.zip");
        version.setSizeBytes(4096L);
        version.setDeleted(false);
        when(versionRepository.findByIdAndAssetIdAndDeletedFalse("version-1", "asset-1"))
                .thenReturn(Optional.of(version));
        return version;
    }

    private static CodeArchiveEntry entry(String path, long size) {
        return new CodeArchiveEntry(path, 0, size, size, 0, 100L);
    }

    private static CodeAsset asset() {
        CodeAsset asset = new CodeAsset();
        asset.setId("asset-1");
        asset.setOwnerUserId(7);
        asset.setDeleted(false);
        return asset;
    }

    private static CodeWorkspace workspace(String id, String base, String status, long revision) {
        CodeWorkspace workspace = new CodeWorkspace();
        workspace.setId(id);
        workspace.setAssetId("asset-1");
        workspace.setOwnerUserId(7);
        workspace.setBaseVersionId(base);
        workspace.setStatus(status);
        workspace.setRevision(revision);
        workspace.setCreatedAt(Instant.now());
        workspace.setUpdatedAt(Instant.now());
        workspace.setDeleted(false);
        return workspace;
    }

    private static CodeWorkspaceFileDelta upsertDelta(String path, String text) {
        byte[] bytes = bytes(text);
        CodeWorkspaceFileDelta delta = new CodeWorkspaceFileDelta();
        delta.setId("delta-" + path.replace('/', '-'));
        delta.setWorkspaceId("workspace-1");
        delta.setPath(path);
        delta.setOperation("UPSERT");
        delta.setContentBytes(bytes);
        delta.setContentHash(new CodeFilePolicy().sha256(bytes));
        delta.setSizeBytes((long) bytes.length);
        delta.setCreatedAt(Instant.now());
        delta.setUpdatedAt(Instant.now());
        return delta;
    }

    private static CodeWorkspaceFileDelta deleteDelta(String path) {
        CodeWorkspaceFileDelta delta = new CodeWorkspaceFileDelta();
        delta.setId("delta-" + path.replace('/', '-'));
        delta.setWorkspaceId("workspace-1");
        delta.setPath(path);
        delta.setOperation("DELETE");
        delta.setCreatedAt(Instant.now());
        delta.setUpdatedAt(Instant.now());
        return delta;
    }

    private static CodeWorkspaceFileDeltaRepository.DeltaMetadata metadataOnlyDelta(
            String path,
            long size,
            String hash
    ) {
        return new TestDeltaMetadata(path, "UPSERT", size, hash);
    }

    private static CodeWorkspaceFileDeltaRepository.DeltaMetadata metadata(
            CodeWorkspaceFileDelta delta
    ) {
        return new TestDeltaMetadata(
                delta.getPath(),
                delta.getOperation(),
                delta.getSizeBytes(),
                delta.getContentHash()
        );
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record TestDeltaMetadata(
            String path,
            String operation,
            Long sizeBytes,
            String contentHash
    ) implements CodeWorkspaceFileDeltaRepository.DeltaMetadata {

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public String getOperation() {
            return operation;
        }

        @Override
        public Long getSizeBytes() {
            return sizeBytes;
        }

        @Override
        public String getContentHash() {
            return contentHash;
        }
    }
}
