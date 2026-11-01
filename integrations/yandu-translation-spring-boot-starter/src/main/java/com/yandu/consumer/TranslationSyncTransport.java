package com.yandu.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.function.Supplier;

/** Optional transport module contract for low-latency release-change notices. */
public interface TranslationSyncTransport {

    String mode();

    AutoCloseable connect(TranslationSyncProperties properties, ObjectMapper json,
                          Supplier<String> currentChecksum, Runnable onChange);
}
