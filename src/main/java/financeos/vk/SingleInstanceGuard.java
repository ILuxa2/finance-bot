package financeos.vk;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Prevents two local VK listeners from using the same saved state. */
public final class SingleInstanceGuard implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private SingleInstanceGuard(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static SingleInstanceGuard acquire(Path statePath) throws IOException {
        Path lockPath = statePath.toAbsolutePath().resolveSibling(
                statePath.getFileName() + ".running.lock");
        Files.createDirectories(lockPath.getParent());
        FileChannel channel = FileChannel.open(lockPath,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException occupied) {
                lock = null;
            }
            if (lock == null) throw new IOException("Another local VK bot is already using this state");
            return new SingleInstanceGuard(channel, lock);
        } catch (IOException failed) {
            channel.close();
            throw failed;
        }
    }

    @Override public void close() throws IOException {
        try {
            lock.release();
        } finally {
            channel.close();
        }
    }
}
