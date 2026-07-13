package com.rom.cellarbridge.inventory;

public enum SupplyType {
  DOMESTIC_ON_HAND(true),
  BONDED_ON_HAND(true),
  HONG_KONG_ON_HAND(true),
  IN_TRANSIT_PRESALE(false),
  OVERSEAS_SOURCING(false);

  private final boolean automaticallyReservable;

  SupplyType(boolean automaticallyReservable) {
    this.automaticallyReservable = automaticallyReservable;
  }

  public boolean automaticallyReservable() {
    return automaticallyReservable;
  }
}
