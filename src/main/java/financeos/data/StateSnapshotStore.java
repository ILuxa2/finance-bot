package financeos.data;

import java.io.IOException;
import java.util.Optional;

/** Storage for complete, byte-identical Java bot state snapshots. */
public interface StateSnapshotStore {
    Optional<byte[]> latest() throws IOException, InterruptedException;
    void save(byte[] stateJson) throws IOException, InterruptedException;
}
