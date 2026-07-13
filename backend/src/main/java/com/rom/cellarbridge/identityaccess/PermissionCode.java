package com.rom.cellarbridge.identityaccess;

import java.util.Arrays;

public enum PermissionCode {
  PARTNER_READ("partner:read"),
  PARTNER_CREATE("partner:create"),
  PARTNER_SUBMIT("partner:submit"),
  PARTNER_REVIEW("partner:review"),
  CATALOG_READ("catalog:read"),
  CATALOG_MAINTAIN("catalog:maintain"),
  QUOTATION_READ("quotation:read"),
  QUOTATION_CREATE("quotation:create"),
  QUOTATION_SUBMIT("quotation:submit"),
  QUOTATION_APPROVE("quotation:approve"),
  QUOTATION_ISSUE("quotation:issue"),
  QUOTATION_READ_COMMERCIAL_SENSITIVE("quotation:read-commercial-sensitive"),
  ORDER_READ("order:read"),
  ORDER_CANCEL("order:cancel"),
  INVENTORY_READ("inventory:read"),
  INVENTORY_READ_EXACT("inventory:read-exact"),
  INVENTORY_RESERVE("inventory:reserve"),
  INVENTORY_ADJUST("inventory:adjust"),
  FULFILLMENT_READ("fulfillment:read"),
  FULFILLMENT_OPERATE("fulfillment:operate"),
  EXCEPTION_READ("exception:read"),
  EXCEPTION_ASSIGN("exception:assign"),
  EXCEPTION_RECOVER("exception:recover"),
  SETTLEMENT_READ("settlement:read"),
  SETTLEMENT_RECORD_PAYMENT("settlement:record-payment"),
  SETTLEMENT_REVERSE_PAYMENT("settlement:reverse-payment"),
  REPORTING_READ("reporting:read"),
  AUDIT_READ("audit:read"),
  EVENT_PUBLICATION_READ("event-publication:read"),
  EVENT_PUBLICATION_REPLAY("event-publication:replay"),
  ADMINISTRATION_MANAGE_ACCESS("administration:manage-access");

  private final String value;

  PermissionCode(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static PermissionCode fromValue(String value) {
    return Arrays.stream(values())
        .filter(permission -> permission.value.equals(value))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown permission code: " + value));
  }
}
