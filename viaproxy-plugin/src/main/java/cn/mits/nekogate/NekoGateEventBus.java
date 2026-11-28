package cn.mits.nekogate;

import cn.mits.nekogate.api.NekoGateAPI;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

final class NekoGateEventBus implements NekoGateAPI.EventBus {
    private record Handler<T>(Consumer<? super T> listener) { }
    private final java.util.concurrent.ConcurrentMap<Class<?>, CopyOnWriteArrayList<Handler<?>>> handlers =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public <T> NekoGateAPI.Subscription subscribe(Class<T> eventType, Consumer<? super T> listener) {
        if (eventType == null || listener == null) throw new IllegalArgumentException("eventType and listener are required");
        Handler<T> handler = new Handler<>(listener);
        this.handlers.computeIfAbsent(eventType, ignored -> new CopyOnWriteArrayList<>()).add(handler);
        return () -> {
            List<Handler<?>> registered = this.handlers.get(eventType);
            if (registered != null) {
                registered.remove(handler);
                if (registered.isEmpty()) this.handlers.remove(eventType, registered);
            }
        };
    }

    boolean hasListeners(Class<?> eventType) {
        List<Handler<?>> registered = this.handlers.get(eventType);
        return registered != null && !registered.isEmpty();
    }

    @SuppressWarnings("unchecked")
    void publish(Object event) {
        if (event == null) return;
        List<Handler<?>> registered = this.handlers.get(event.getClass());
        if (registered == null) return;
        for (Handler<?> raw : registered) {
            try {
                ((Consumer<Object>) raw.listener()).accept(event);
            } catch (Throwable error) {
                System.err.println("[NekoGate] Extension event listener failed: " + error);
            }
        }
    }

    void clear() {
        this.handlers.clear();
    }
}
