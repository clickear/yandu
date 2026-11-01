package com.yandu.sync;

import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;

/** Application-facing port used by optional release-notification transports. */
public interface ReleaseSyncService {

    AutoCloseable subscribe(Subscription subscription, Consumer<ReleaseNotice> listener);

    Optional<ReleaseNotice> latest(Subscription subscription);

    record Subscription(long projectId, String locale, String format, String afterChecksum) { }

    record ReleaseNotice(String id, long projectId, String locale, String format, long version,
                         String checksumSha256, Instant publishedAt) { }
}
