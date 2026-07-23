package com.rom.cellarbridge.catalog.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.catalog.CatalogQueryException;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.CursorPosition;
import com.rom.cellarbridge.catalog.internal.application.CatalogSearchRepository.SearchSort;
import com.rom.cellarbridge.identityaccess.TenantId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

class CatalogCursorCodecTest {

  private static final String SECRET = "catalog-cursor-test-secret-with-32-characters";
  private static final Instant NOW = Instant.parse("2026-07-23T12:00:00Z");
  private static final Duration TTL = Duration.ofMinutes(15);
  private static final TenantId TENANT =
      TenantId.of(UUID.fromString("10000000-0000-4000-8000-000000000001"));
  private static final TenantId OTHER_TENANT =
      TenantId.of(UUID.fromString("20000000-0000-4000-8000-000000000001"));
  private static final CursorPosition POSITION =
      new CursorPosition(
          "moonlit terrace",
          "CB-MTV-2019-750X6",
          UUID.fromString("34000000-0000-4000-8000-000000000001"));

  private final CatalogCursorCodec codec =
      new CatalogCursorCodec(SECRET, TTL, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void roundTripsOnlyForTheBoundTenantFilterAndSort() {
    String cursor = codec.encode(TENANT, "filter-one", SearchSort.RELEVANCE, POSITION);

    assertThat(codec.decode(TENANT, "filter-one", SearchSort.RELEVANCE, cursor))
        .isEqualTo(POSITION);
    assertRejected(() -> codec.decode(OTHER_TENANT, "filter-one", SearchSort.RELEVANCE, cursor));
    assertRejected(() -> codec.decode(TENANT, "filter-two", SearchSort.RELEVANCE, cursor));
    assertRejected(() -> codec.decode(TENANT, "filter-one", SearchSort.NAME, cursor));
  }

  @Test
  void rejectsTamperedExpiredAndFutureDatedCursors() {
    String cursor = codec.encode(TENANT, "filter-one", SearchSort.RELEVANCE, POSITION);
    String tampered = cursor.substring(0, cursor.length() - 1) + "A";
    CatalogCursorCodec expiredCodec =
        new CatalogCursorCodec(
            SECRET, TTL, Clock.fixed(NOW.plus(TTL).plusSeconds(1), ZoneOffset.UTC));
    CatalogCursorCodec futureCodec =
        new CatalogCursorCodec(SECRET, TTL, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC));
    String future = futureCodec.encode(TENANT, "filter-one", SearchSort.RELEVANCE, POSITION);

    assertRejected(() -> codec.decode(TENANT, "filter-one", SearchSort.RELEVANCE, tampered));
    assertRejected(() -> expiredCodec.decode(TENANT, "filter-one", SearchSort.RELEVANCE, cursor));
    assertRejected(() -> codec.decode(TENANT, "filter-one", SearchSort.RELEVANCE, future));
  }

  private static void assertRejected(ThrowingCallable operation) {
    assertThatThrownBy(operation).isInstanceOf(CatalogQueryException.class);
  }
}
