package com.yandu.web;

import com.yandu.release.ReleasePublishedEvent;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionPhase;

/** Fans out committed published-version notices to connected public consumers. */
@Component
public class ReleaseChangeHub {

    private final ConcurrentHashMap<ChannelKey, CopyOnWriteArrayList<Consumer<ReleasePublishedEvent>>> listeners =
            new ConcurrentHashMap<>();

    public AutoCloseable subscribe(long projectId, String locale, String format,
                                   Consumer<ReleasePublishedEvent> listener) {
        var key = new ChannelKey(projectId, locale, format);
        var subscribers = listeners.computeIfAbsent(key, ignored -> new CopyOnWriteArrayList<>());
        subscribers.add(listener);
        return () -> {
            subscribers.remove(listener);
            if (subscribers.isEmpty()) listeners.remove(key, subscribers);
        };
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onPublished(ReleasePublishedEvent event) {
        var subscribers = listeners.get(new ChannelKey(event.projectId(), event.locale(), event.format()));
        if (subscribers != null) subscribers.forEach(listener -> listener.accept(event));
    }

    private record ChannelKey(long projectId, String locale, String format) { }
}
