package com.rom.cellarbridge.inventory.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.inventory.QuantityUnit;
import com.rom.cellarbridge.inventory.SupplyType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryLotTest {

  @Test
  void calculatesAvailableQuantityAndRejectsImpossibleBalances() {
    InventoryLot lot =
        new InventoryLot(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            QuantityUnit.CASE,
            new BigDecimal("18"),
            new BigDecimal("3"));

    assertThat(lot.available()).isEqualByComparingTo("15");
    assertThat(lot.quantityUnit()).isEqualTo(QuantityUnit.CASE);
    assertThatThrownBy(
            () ->
                new InventoryLot(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    QuantityUnit.BOTTLE,
                    BigDecimal.ONE,
                    new BigDecimal("2")))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                new InventoryLot(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    BigDecimal.ONE,
                    BigDecimal.ZERO))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("quantityUnit");
  }

  @Test
  void onlyOnHandSupplyTypesAreAutomaticallyReservable() {
    assertThat(SupplyType.DOMESTIC_ON_HAND.automaticallyReservable()).isTrue();
    assertThat(SupplyType.BONDED_ON_HAND.automaticallyReservable()).isTrue();
    assertThat(SupplyType.HONG_KONG_ON_HAND.automaticallyReservable()).isTrue();
    assertThat(SupplyType.IN_TRANSIT_PRESALE.automaticallyReservable()).isFalse();
    assertThat(SupplyType.OVERSEAS_SOURCING.automaticallyReservable()).isFalse();
  }
}
