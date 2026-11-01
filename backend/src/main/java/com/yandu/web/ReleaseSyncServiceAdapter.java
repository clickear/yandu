package com.yandu.web;

import com.yandu.release.ReleaseApplicationService;
import com.yandu.release.ReleasePublishedEvent;
import com.yandu.sync.ReleaseSyncService;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Bridges release application events and reads to the optional realtime transport contract. */
@Component
public class ReleaseSyncServiceAdapter implements ReleaseSyncService {

    private final ReleaseChangeHub changes;
    private final ReleaseApplicationService releases;

    public ReleaseSyncServiceAdapter(ReleaseChangeHub changes, ReleaseApplicationService releases) {
        this.changes = changes;
        this.releases = releases;
    }

    @Override
    public AutoCloseable subscribe(Subscription subscription, java.util.function.Consumer<ReleaseNotice> listener) {
        return changes.subscribe(subscription.projectId(), subscription.locale(), subscription.format(),
                event -> listener.accept(toNotice(event)));
    }

    @Override
    public Optional<ReleaseNotice> latest(Subscription subscription) {
        try {
            return Optional.of(toNotice(releases.latestPublished(subscription.projectId(), subscription.locale(),
                    subscription.format())));
        } catch (ReleaseApplicationService.ReleaseNotFoundException notPublished) {
            return Optional.empty();
        }
    }

    private static ReleaseNotice toNotice(ReleasePublishedEvent event) {
        return new ReleaseNotice(Long.toString(event.releaseId()), event.projectId(), event.locale(), event.format(),
                event.version(), event.checksumSha256(), event.publishedAt());
    }

    private static ReleaseNotice toNotice(ReleaseApplicationService.PublicReleaseMetadata metadata) {
        return new ReleaseNotice(metadata.id(), Long.parseLong(metadata.projectId()), metadata.locale(),
                metadata.format(), metadata.version(), metadata.checksumSha256(), metadata.publishedAt());
    }
}
