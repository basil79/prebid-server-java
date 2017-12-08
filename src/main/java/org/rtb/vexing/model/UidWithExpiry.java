package org.rtb.vexing.model;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import lombok.experimental.FieldDefaults;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

@ToString
@EqualsAndHashCode
@AllArgsConstructor
@FieldDefaults(makeFinal = true, level = AccessLevel.PUBLIC)
public class UidWithExpiry {

    private static final long LIVE_TTL_MS = Duration.ofDays(14).toMillis();
    private static final long EXPIRED_TTL_MS = Duration.ofMinutes(5).toMillis();

    String uid;

    ZonedDateTime expires;

    public static UidWithExpiry live(String uid) {
        return create(uid, LIVE_TTL_MS);
    }

    public static UidWithExpiry expired(String uid) {
        return create(uid, -EXPIRED_TTL_MS);
    }

    private static UidWithExpiry create(String uid, long ttlMs) {
        return new UidWithExpiry(uid, ZonedDateTime.now(Clock.systemUTC()).plus(ttlMs, ChronoUnit.MILLIS));
    }
}
