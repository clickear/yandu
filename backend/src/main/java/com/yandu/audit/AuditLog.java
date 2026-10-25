package com.yandu.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Shared project-scoped audit writer and reader; audit rows are append-only. */
@Service
public class AuditLog {

    private final JdbcTemplate jdbc;
    private final LongSupplier ids;
    private final Clock clock;
    private final ObjectMapper json;

    public AuditLog(JdbcTemplate jdbc, LongSupplier ids, Clock clock, ObjectMapper json) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.ids = Objects.requireNonNull(ids);
        this.clock = Objects.requireNonNull(clock);
        this.json = Objects.requireNonNull(json);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long projectId, long actorId, String action, String resourceType,
                       Long resourceId, Map<String, ?> details) {
        if (projectId <= 0 || actorId <= 0 || action == null || action.isBlank()
                || resourceType == null || resourceType.isBlank()) {
            throw new IllegalArgumentException("Project, actor, action and resource type are required");
        }
        String detailsJson;
        try {
            detailsJson = details == null || details.isEmpty() ? null : json.writeValueAsString(details);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Audit metadata cannot be serialized", exception);
        }
        jdbc.update("""
                INSERT INTO audit_log
                    (id, project_id, actor_id, action, resource_type, resource_id, details_json, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, ids.getAsLong(), projectId, actorId, action, resourceType,
                resourceId == null ? null : Long.toString(resourceId), detailsJson,
                Timestamp.from(clock.instant()));
    }

    public AuditPage page(long projectId, int page, int pageSize) {
        if (projectId <= 0 || page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Project, page and page size are invalid");
        }
        int offset = Math.multiplyExact(page - 1, pageSize);
        var items = jdbc.query("""
                SELECT id, project_id, actor_id, action, resource_type, resource_id,
                       details_json, occurred_at
                  FROM audit_log WHERE project_id = ?
                 ORDER BY occurred_at DESC, id DESC LIMIT ? OFFSET ?
                """, (row, index) -> new AuditEntry(Long.toString(row.getLong("id")),
                Long.toString(row.getLong("project_id")), Long.toString(row.getLong("actor_id")),
                row.getString("action"), row.getString("resource_type"),
                row.getString("resource_id"), row.getString("details_json"),
                row.getTimestamp("occurred_at").toInstant()), projectId, pageSize, offset);
        var total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log WHERE project_id = ?",
                Long.class, projectId);
        return new AuditPage(page, pageSize, total == null ? 0 : total, items);
    }

    public record AuditEntry(String id, String projectId, String actorId, String action,
                             String resourceType, String resourceId, String detailsJson,
                             Instant occurredAt) { }

    public record AuditPage(int page, int pageSize, long total, List<AuditEntry> items) { }
}
