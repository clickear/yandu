package com.yandu.identity;

import java.time.Clock;
import java.util.function.LongSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Resolves a verified OIDC principal to the platform's numeric internal user ID. */
@Component
public class CurrentActor {

    private final JdbcTemplate jdbc;
    private final LongSupplier ids;
    private final Clock clock;

    public CurrentActor(JdbcTemplate jdbc, LongSupplier ids, Clock clock) {
        this.jdbc = jdbc;
        this.ids = ids;
        this.clock = clock;
    }

    @Transactional
    public long resolve(Jwt principal) {
        var issuer = principal.getIssuer();
        var subject = principal.getSubject();
        if (issuer == null || issuer.toString().length() > 255
                || subject == null || subject.isBlank() || subject.length() > 255) {
            throw new AccessDeniedException("The authenticated identity has no supported issuer/subject");
        }

        var now = clock.instant();
        jdbc.update("""
                INSERT IGNORE INTO external_identities (user_id, issuer, subject, created_at)
                VALUES (?, ?, ?, ?)
                """, ids.getAsLong(), issuer.toString(), subject, now);
        return jdbc.queryForObject("""
                SELECT user_id FROM external_identities WHERE issuer = ? AND subject = ?
                """, Long.class, issuer.toString(), subject);
    }
}
