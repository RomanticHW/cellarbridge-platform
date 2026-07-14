package com.rom.cellarbridge.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class QuotationSnapshotHashV1Test {

  private static final String BARE =
      "a61b70c8847bbc58e64fa9d3dacdcf1277868166ea2c6af5762e89a010a96fad";

  @Test
  void keepsTheCurrentBareFormat() {
    assertThat(QuotationSnapshotHashV1.isCurrentFormat(BARE)).isTrue();
    assertThat(QuotationSnapshotHashV1.isLegacyPrefixedFormat(BARE)).isFalse();
    assertThat(QuotationSnapshotHashV1.normalizeIncomingHash(BARE)).isEqualTo(BARE);
    assertThat(QuotationSnapshotHashV1.normalizeStoredHash(BARE)).isEqualTo(BARE);
  }

  @Test
  void removesTheExactLegacyPrefix() {
    String legacy = "sha256:" + BARE;
    assertThat(QuotationSnapshotHashV1.isCurrentFormat(legacy)).isFalse();
    assertThat(QuotationSnapshotHashV1.isLegacyPrefixedFormat(legacy)).isTrue();
    assertThat(QuotationSnapshotHashV1.normalizeIncomingHash(legacy)).isEqualTo(BARE);
    assertThat(QuotationSnapshotHashV1.normalizeStoredHash(legacy)).isEqualTo(BARE);
  }

  @ParameterizedTest
  @MethodSource("invalidHashes")
  void rejectsEveryOtherFormat(String value) {
    assertThat(QuotationSnapshotHashV1.isCurrentFormat(value)).isFalse();
    assertThat(QuotationSnapshotHashV1.isLegacyPrefixedFormat(value)).isFalse();
    assertThatThrownBy(() -> QuotationSnapshotHashV1.normalizeIncomingHash(value))
        .isInstanceOf(QuotationSnapshotHashV1.InvalidSnapshotHashFormatException.class);
    assertThatThrownBy(() -> QuotationSnapshotHashV1.normalizeStoredHash(value))
        .isInstanceOf(QuotationSnapshotHashV1.InvalidSnapshotHashFormatException.class);
  }

  private static Stream<String> invalidHashes() {
    return Stream.of(
        null,
        "",
        " " + BARE,
        BARE + " ",
        BARE.toUpperCase(),
        "SHA256:" + BARE,
        "sha256:" + BARE.toUpperCase(),
        "sha512:" + BARE,
        "sha256:sha256:" + BARE,
        "a".repeat(63),
        "a".repeat(65));
  }
}
