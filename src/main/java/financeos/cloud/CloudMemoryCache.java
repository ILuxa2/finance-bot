package financeos.cloud;

import financeos.data.LegacyUserData;
import financeos.vk.BotLocalState;
import java.net.URI;
import java.util.*;

/** Bounded, per-handler memory. Raw snapshots are candidates until Object Storage validates their ETag. */
public final class CloudMemoryCache {
    private static final int MAX_RAW_BYTES = 4 * 1024 * 1024;
    private static final int MAX_BASE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_STATE_CHARS = 4 * 1024 * 1024;
    private final LinkedHashMap<URI, ObjectStorageStateStore.Snapshot> snapshots = new LinkedHashMap<>(4, .75f, true);
    private byte[] baseBytes;
    private LegacyUserData baseData;
    private String encodedState;
    private LegacyUserData stateBase;
    private long stateOwner;
    private Optional<BotLocalState> decodedState;
    private long validations, baseHits, stateHits;
    @FunctionalInterface public interface Loader<T> { T load() throws Exception; }
    public record Stats(int rawEntries, long validatedHits, long baseHits, long stateHits) {}

    public synchronized Optional<ObjectStorageStateStore.Snapshot> snapshot(URI key) {
        return Optional.ofNullable(snapshots.get(key));
    }
    public synchronized void remember(URI key, ObjectStorageStateStore.Snapshot value) {
        snapshots.remove(key);
        if (value.bytes().length > MAX_RAW_BYTES) return;
        snapshots.put(key, value);
        while (snapshots.size() > 2) snapshots.remove(snapshots.keySet().iterator().next());
    }
    public synchronized void invalidate(URI key) { snapshots.remove(key); }
    public synchronized void validated() { validations++; }
    public synchronized Stats stats() { return new Stats(snapshots.size(), validations, baseHits, stateHits); }

    public synchronized LegacyUserData base(byte[] bytes, Loader<LegacyUserData> loader) throws Exception {
        if (baseBytes != null && Arrays.equals(baseBytes, bytes)) { baseHits++; return baseData; }
        var loaded = loader.load();
        // Source records do not themselves copy transaction/plan lists. Freeze before sharing them.
        var frozen = new LegacyUserData(loaded.startingBalance(), loaded.snapshotTime(),
                List.copyOf(loaded.transactions()), List.copyOf(loaded.plans()), loaded.extras());
        baseBytes = null; baseData = null;
        if (bytes.length <= MAX_BASE_BYTES) { baseBytes = bytes.clone(); baseData = frozen; }
        return frozen;
    }
    public synchronized Optional<BotLocalState> state(String encoded, LegacyUserData base, long owner,
                                                      Loader<Optional<BotLocalState>> loader) throws Exception {
        if (encodedState != null && stateBase == base && stateOwner == owner && encodedState.equals(encoded)) {
            stateHits++; return decodedState;
        }
        var loaded = loader.load();
        rememberState(encoded, base, owner, loaded);
        return loaded;
    }
    public synchronized void rememberState(String encoded, LegacyUserData base, long owner, Optional<BotLocalState> value) {
        encodedState = null; stateBase = null; decodedState = null;
        if (encoded.length() > MAX_STATE_CHARS) return;
        encodedState = encoded; stateBase = base; stateOwner = owner; decodedState = value;
    }
}
