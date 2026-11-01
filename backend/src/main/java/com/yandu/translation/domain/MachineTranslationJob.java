package com.yandu.translation.domain;

import java.util.Objects;

/** Machine-translation job aggregate; its summary state is derived from item states. */
public final class MachineTranslationJob {

    private final long id;
    private final long totalCount;
    private long completedCount;
    private long skippedCount;
    private long failedCount;
    private Status status;
    private String error;

    public MachineTranslationJob(long id, long totalCount) {
        if (id <= 0 || totalCount < 0) throw new IllegalArgumentException("Job id and total count are invalid");
        this.id = id;
        this.totalCount = totalCount;
    }

    /** Reconciles the durable summary after an item-state transition within the job transaction. */
    public void reconcile(ItemCounts items) {
        Objects.requireNonNull(items, "items");
        long accounted = Math.addExact(Math.addExact(items.queued(), items.running()),
                Math.addExact(Math.addExact(items.completed(), items.skipped()), items.failed()));
        if (accounted != totalCount) {
            throw new IllegalStateException("Machine translation item count does not match the job total");
        }

        completedCount = items.completed();
        skippedCount = items.skipped();
        failedCount = items.failed();
        error = failedCount == 0 ? null : items.firstFailure();
        if (items.running() > 0) status = Status.RUNNING;
        else if (items.queued() > 0) status = Status.QUEUED;
        else if (failedCount > 0 && completedCount + skippedCount > 0) status = Status.PARTIAL;
        else if (failedCount > 0) status = Status.FAILED;
        else status = Status.COMPLETED;
    }

    public long id() { return id; }
    public long totalCount() { return totalCount; }
    public long completedCount() { return completedCount; }
    public long skippedCount() { return skippedCount; }
    public long failedCount() { return failedCount; }
    public Status status() { return status; }
    public String error() { return error; }

    public record ItemCounts(long queued, long running, long completed, long skipped, long failed,
                             String firstFailure) {
        public ItemCounts {
            if (queued < 0 || running < 0 || completed < 0 || skipped < 0 || failed < 0) {
                throw new IllegalArgumentException("Machine translation item counts cannot be negative");
            }
            if (failed == 0 && firstFailure != null) {
                throw new IllegalArgumentException("A job without failed items cannot expose an item error");
            }
        }
    }

    public enum Status { QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED }
}
