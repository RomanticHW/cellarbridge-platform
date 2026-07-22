package com.rom.cellarbridge.exceptioncenter;

public enum RecoveryAction {
  RETRY_RESERVATION,
  RETRY_FULFILLMENT_STEP,
  RESUME_FULFILLMENT_PLAN,
  REPLAY_PUBLICATION,
  MANUAL_ACKNOWLEDGE
}
