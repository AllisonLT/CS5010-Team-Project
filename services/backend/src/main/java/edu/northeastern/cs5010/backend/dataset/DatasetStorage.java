package edu.northeastern.cs5010.backend.dataset;

import edu.northeastern.cs5010.backend.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Backend-managed files under {@code app.storage.upload-directory} (docs/data_model.md, "Local CSV storage").
 *
 * <pre>
 * tmp/&lt;uuid&gt;.part              upload in progress
 * datasets/&lt;id&gt;/source.csv     exact bytes the user uploaded
 * datasets/&lt;id&gt;/minute.csv     one-minute means used for predictions
 * </pre>
 *
 * The directories are created, and checked for write access, when the backend starts.
 */
@Component
public class DatasetStorage {
    public static final String SOURCE = "source.csv";
    public static final String MINUTE = "minute.csv";
    private static final Logger log = LoggerFactory.getLogger(DatasetStorage.class);

    private final Path root;
    private final Path tmp;
    private final Path datasets;

    public DatasetStorage(AppProperties properties) {
        this.root = Path.of(properties.storage().uploadDirectory()).toAbsolutePath().normalize();
        this.tmp = root.resolve("tmp");
        this.datasets = root.resolve("datasets");
        try {
            Files.createDirectories(tmp);
            Files.createDirectories(datasets);
        } catch (IOException e) {
            throw new IllegalStateException("app.storage.upload-directory '" + root
                    + "' cannot be created: " + e.getMessage(), e);
        }
        if (!Files.isWritable(tmp) || !Files.isWritable(datasets)) {
            throw new IllegalStateException("app.storage.upload-directory '" + root + "' is not writable");
        }
    }

    /** A file in {@code tmp/} with its SHA-256 and size. The caller deletes or moves it. */
    public record TempFile(Path path, String sha256, long sizeBytes) {
    }

    /** Copies the upload to {@code tmp/<uuid>.part}, computing its SHA-256 on the way. */
    public TempFile receive(InputStream upload, long maxBytes) throws IOException {
        Path part = tmp.resolve(UUID.randomUUID() + ".part");
        MessageDigest digest = sha256();
        long size = 0;
        try (InputStream in = upload; OutputStream out = new DigestOutputStream(Files.newOutputStream(part), digest)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                size += read;
                if (size > maxBytes) {
                    throw new FileTooLargeException(maxBytes);
                }
                out.write(buffer, 0, read);
            }
        } catch (IOException | RuntimeException e) {
            deleteQuietly(part);
            throw e;
        }
        return new TempFile(part, HexFormat.of().formatHex(digest.digest()), size);
    }

    /** Moves the received file to {@code datasets/<id>/source.csv} and returns that folder. */
    public Path store(TempFile file, String datasetId) throws IOException {
        Path folder = folder(datasetId);
        Files.createDirectories(folder);
        Path target = folder.resolve(SOURCE);
        try {
            Files.move(file.path(), target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(file.path(), target, StandardCopyOption.REPLACE_EXISTING);
        }
        return folder;
    }

    public Path folder(String datasetId) {
        // Ids are backend-generated UUIDs; refuse anything else so a path cannot escape the folder.
        UUID.fromString(datasetId);
        return datasets.resolve(datasetId);
    }

    /** Path stored in MongoDB: relative to the upload directory, with forward slashes. */
    public String relative(Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }

    public Path resolve(String relativePath) {
        Path resolved = root.resolve(relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path outside the upload directory: " + relativePath);
        }
        return resolved;
    }

    public void deleteFolder(String datasetId) {
        Path folder = folder(datasetId);
        if (!Files.exists(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            paths.sorted(Comparator.reverseOrder()).forEach(DatasetStorage::deleteQuietly);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Deletes {@code tmp/*.part} files older than {@code age}, left behind by a crash during upload. */
    public int deleteStaleTempFiles(Duration age) {
        Instant cutoff = Instant.now().minus(age);
        int deleted = 0;
        try (DirectoryStream<Path> parts = Files.newDirectoryStream(tmp, "*.part")) {
            for (Path part : parts) {
                FileTime modified = Files.getLastModifiedTime(part);
                if (modified.toInstant().isBefore(cutoff) && Files.deleteIfExists(part)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("Could not clean {}: {}", tmp, e.toString());
        }
        return deleted;
    }

    public static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Could not delete {}: {}", path, e.toString());
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Thrown while receiving, as soon as the upload passes the limit. */
    public static class FileTooLargeException extends RuntimeException {
        public FileTooLargeException(long maxBytes) {
            super("File larger than " + maxBytes + " bytes");
        }
    }
}
