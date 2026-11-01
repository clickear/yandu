package com.yandu.release;

import java.time.Instant;

/** Raised after a committed immutable release becomes available to consumers. */
public record ReleasePublishedEvent(long projectId, String locale, String format, long releaseId,
                                   long version, String checksumSha256, Instant publishedAt) { }
