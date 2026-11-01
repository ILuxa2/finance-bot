package financeos.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.List;

public final class Navigation {
    private final Deque<Screen> backStack = new ArrayDeque<>();
    private Screen current = Screen.MAIN;

    public record State(Screen current, List<Screen> backStack) {
        public State {
            Objects.requireNonNull(current);
            backStack = List.copyOf(backStack);
        }
    }

    public State state() { return new State(current, List.copyOf(backStack)); }

    public void restore(State state) {
        current = state.current();
        backStack.clear();
        backStack.addAll(state.backStack());
    }

    public Screen current() { return current; }

    public Screen open(Screen next) {
        Objects.requireNonNull(next);
        if (next != current) {
            backStack.push(current);
            current = next;
        }
        return current;
    }

    public Screen back() {
        current = backStack.isEmpty() ? Screen.MAIN : backStack.pop();
        return current;
    }

    public Screen main() {
        backStack.clear();
        current = Screen.MAIN;
        return current;
    }
}
