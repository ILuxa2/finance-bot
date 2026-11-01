package financeos.vk;

import financeos.data.StateSnapshotStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Mirrors an atomic local state file to the test copy without delaying VK replies. */
public final class CopyStateMirror implements AutoCloseable {
    private final Path statePath;
    private final Path markerPath;
    private final StateSnapshotStore remote;
    private final Consumer<String> status;
    private ScheduledExecutorService worker;
    private boolean prepared;
    private boolean failing;
    private boolean queued;

    public CopyStateMirror(Path statePath, Path markerPath, StateSnapshotStore remote,
                           Consumer<String> status) {
        this.statePath = statePath.toAbsolutePath();
        this.markerPath = markerPath.toAbsolutePath();
        this.remote = Objects.requireNonNull(remote);
        this.status = Objects.requireNonNull(status);
    }

    /** Reconciles a previous interrupted upload before the bot loads its state. */
    public synchronized void prepare() throws IOException, InterruptedException {
        if (prepared) throw new IllegalStateException("Copy state mirror already prepared");
        Optional<byte[]> remoteState = remote.latest();
        boolean localExists = Files.exists(statePath);
        String marker = readMarker();
        if (!localExists && remoteState.isEmpty()) {
            if (marker != null) throw new IOException("State sync marker exists but both copies are missing");
        } else if (!localExists) {
            Files.createDirectories(statePath.getParent());
            byte[] bytes = remoteState.orElseThrow();
            Files.write(statePath, bytes, StandardOpenOption.CREATE_NEW);
            writeMarker(hash(bytes));
            status.accept("Локальное состояние восстановлено из тестовой копии.");
        } else if (remoteState.isEmpty()) {
            if (marker != null) throw new IOException("Remote state disappeared; manual review required");
            byte[] local = Files.readAllBytes(statePath);
            remote.save(local);
            writeMarker(hash(local));
            status.accept("Локальное состояние сохранено в тестовую копию.");
        } else {
            byte[] local = Files.readAllBytes(statePath);
            String localHash = hash(local);
            String remoteHash = hash(remoteState.orElseThrow());
            if (localHash.equals(remoteHash)) {
                writeMarker(localHash);
            } else if (remoteHash.equals(marker)) {
                remote.save(local);
                writeMarker(localHash);
                status.accept("Отложенное сохранение в тестовую копию завершено.");
            } else {
                throw new IOException("Local and remote Java state differ; manual review required");
            }
        }
        prepared = true;
    }

    public synchronized void start() {
        if (!prepared || worker != null) throw new IllegalStateException("Copy state mirror not ready");
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "financeos-test-copy-sync");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::attempt, 20, 20, TimeUnit.SECONDS);
    }

    /** Called after an atomic local save. The network operation runs in the background. */
    public synchronized void signal() {
        if (worker == null) throw new IllegalStateException("Copy state mirror not started");
        if (!queued) {
            queued = true;
            worker.schedule(() -> {
                synchronized (this) { queued = false; }
                attempt();
            }, 2, TimeUnit.SECONDS);
        }
    }

    private void attempt() {
        try {
            boolean changed = syncOnce();
            if (changed || failing) status.accept("Состояние сохранено в тестовую копию.");
            failing = false;
        } catch (Exception unavailable) {
            if (!failing) {
                status.accept(unavailable.getMessage() != null
                        && unavailable.getMessage().startsWith("Remote Java state changed unexpectedly")
                        ? "Тестовая копия изменилась отдельно. Локальные данные сохранены; нужна сверка перед продолжением синхронизации."
                        : "Тестовая копия временно недоступна; локальные данные сохранены. Повторю синхронизацию.");
            }
            failing = true;
        }
    }

    /** Package-visible for deterministic local tests. */
    synchronized boolean syncOnce() throws IOException, InterruptedException {
        if (!prepared || !Files.exists(statePath)) return false;
        byte[] local = Files.readAllBytes(statePath);
        String localHash = hash(local);
        String marker = readMarker();
        if (localHash.equals(marker)) return false;
        Optional<byte[]> remoteState = remote.latest();
        if (remoteState.isPresent() && localHash.equals(hash(remoteState.get()))) {
            // An earlier upload succeeded but the process stopped before recording its marker.
            writeMarker(localHash);
            return true;
        }
        if (remoteState.isEmpty() ? marker != null : !hash(remoteState.get()).equals(marker)) {
            throw new IOException("Remote Java state changed unexpectedly; refusing to overwrite it");
        }
        remote.save(local);
        writeMarker(localHash);
        return true;
    }

    private String readMarker() throws IOException {
        if (!Files.exists(markerPath)) return null;
        String value = Files.readString(markerPath, StandardCharsets.US_ASCII).strip();
        if (!value.matches("[0-9a-f]{64}")) throw new IOException("Invalid copy sync marker");
        return value;
    }

    private void writeMarker(String value) throws IOException {
        Files.createDirectories(markerPath.getParent());
        Path temporary = markerPath.resolveSibling(markerPath.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            Files.writeString(temporary, value, StandardCharsets.US_ASCII, StandardOpenOption.CREATE_NEW);
            try {
                Files.move(temporary, markerPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, markerPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @Override public synchronized void close() {
        if (worker != null) worker.shutdown();
    }
}
