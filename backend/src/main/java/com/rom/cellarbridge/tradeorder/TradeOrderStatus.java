package com.rom.cellarbridge.tradeorder;

/** Approved Trade Order lifecycle states. */
public enum TradeOrderStatus {
  PENDING_RESERVATION,
  RESERVED,
  RESERVATION_FAILED,
  READY_FOR_FULFILLMENT,
  IN_FULFILLMENT,
  FULFILLED,
  CANCELLATION_PENDING,
  CANCELLED,
  CANCELLATION_FAILED
}
