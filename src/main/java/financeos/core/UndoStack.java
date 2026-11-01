package financeos.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.List;

public final class UndoStack<T> {
    private final Deque<T> history = new ArrayDeque<>();
    private final int capacity;

    public UndoStack(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Capacity must be positive");
        this.capacity = capacity;
    }

    public void remember(T priorState) {
        if (priorState == null) throw new IllegalArgumentException("State is required");
        history.push(priorState);
        while (history.size() > capacity) history.removeLast();
    }

    public Optional<T> undo() {
        return history.isEmpty() ? Optional.empty() : Optional.of(history.pop());
    }

    public List<T> newestFirst() {
        return List.copyOf(history);
    }

    public void restoreNewestFirst(List<T> states) {
        if (states == null || states.size() > capacity || states.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Invalid undo history");
        }
        history.clear();
        for (int i = states.size() - 1; i >= 0; i--) history.push(states.get(i));
    }
}
