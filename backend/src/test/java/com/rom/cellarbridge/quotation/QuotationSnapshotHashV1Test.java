package com.rom.cellarbridge.quotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class QuotationSnapshotHashV1Test {

  private static final String BARE =
      "a61b70c8847bbc58e64fa9d3dacdcf1277868166ea2c6af5762e89a010a96fad";

  @Test
  void normalizesOnlyCurrentAndExactHistoricalFormats() {
    String legacy = "sha256:" + BARE;
    assertThat(QuotationSnapshotHashV1.isCurrentFormat(BARE)).isTrue();
    assertThat(QuotationSnapshotHashV1.isLegacyPrefixedFormat(legacy)).isTrue();
    assertThat(QuotationSnapshotHashV1.normalizeIncomingHash(BARE)).isEqualTo(BARE);
    assertThat(QuotationSnapshotHashV1.normalizeStoredHash(legacy)).isEqualTo(BARE);

    for (String invalid :
        Arrays.asList(
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
            "a".repeat(65))) {
      assertThat(QuotationSnapshotHashV1.isCurrentFormat(invalid)).isFalse();
      assertThat(QuotationSnapshotHashV1.isLegacyPrefixedFormat(invalid)).isFalse();
      assertThatThrownBy(() -> QuotationSnapshotHashV1.normalizeIncomingHash(invalid))
          .isInstanceOf(QuotationSnapshotHashV1.InvalidSnapshotHashFormatException.class);
      assertThatThrownBy(() -> QuotationSnapshotHashV1.normalizeStoredHash(invalid))
          .isInstanceOf(QuotationSnapshotHashV1.InvalidSnapshotHashFormatException.class);
    }
  }
}
