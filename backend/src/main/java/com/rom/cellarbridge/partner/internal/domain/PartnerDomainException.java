package com.rom.cellarbridge.partner.internal.domain;

import java.util.List;

public final class PartnerDomainException extends RuntimeException {

  private final String code;
  private final List<String> fields;

  public PartnerDomainException(String code, String message) {
    this(code, message, List.of());
  }

  public PartnerDomainException(String code, String message, List<String> fields) {
    super(message);
    this.code = code;
    this.fields = List.copyOf(fields);
  }

  public String code() {
    return code;
  }

  public List<String> fields() {
    return fields;
  }
}
