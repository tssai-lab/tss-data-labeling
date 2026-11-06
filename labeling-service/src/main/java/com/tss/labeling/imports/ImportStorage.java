package com.tss.labeling.imports;

import org.springframework.stereotype.Component;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import static java.nio.file.StandardOpenOption.*;
import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

@Component
public class ImportStorage {
    private final Path root;

    public ImportStorage(ImportSettings settings) throws IOException {
        Path configured = Path.of(settings.storageRoot()).toAbsolutePath().normalize();
        Files.createDirectories(configured);
        root = configured.toRealPath();
    }

    Path batchDirectory(UUID batch) throws IOException {
        return directory(root.resolve(batch.toString()));
    }

    Path sampleDirectory(UUID batch) throws IOException {
        return directory(batchDirectory(batch).resolve("samples"));
    }

    private Path directory(Path path) throws IOException {
        if (!Files.exists(path, NOFOLLOW_LINKS)) {
            try { Files.createDirectory(path); }
            catch (FileAlreadyExistsException ignored) { }
        }
        if (!Files.isDirectory(path, NOFOLLOW_LINKS)) throw new IOException("存储目录不可用");
        return path;
    }

    Path archive(UUID batch) throws IOException { return batchDirectory(batch).resolve("archive.zip"); }

    void append(UUID batch, long offset, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(archive(batch), CREATE, READ, WRITE, NOFOLLOW_LINKS)) {
            if (channel.size() < offset) throw new IOException("上传文件短于数据库进度，需要重新创建批次");
            // A crashed/rolled-back write may have reached disk before the database committed.
            channel.truncate(offset);
            channel.position(offset);
            ByteBuffer data = ByteBuffer.wrap(bytes);
            while (data.hasRemaining()) channel.write(data);
            channel.force(true);
        }
    }

    Path sample(UUID batch, UUID sample) throws IOException {
        Path result = sampleDirectory(batch).resolve(sample + ".bin");
        if (!Files.isRegularFile(result, NOFOLLOW_LINKS)) throw new IOException("样本文件不存在");
        return result;
    }

    static String sha256(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file, NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[65536];
                int length;
                while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
