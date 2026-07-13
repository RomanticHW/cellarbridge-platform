package com.rom.cellarbridge.partner.internal.application;

import com.rom.cellarbridge.partner.PartnerStatus;
import java.util.List;
import org.springframework.http.HttpStatus;

public final class PartnerProblemException extends RuntimeException {

  private final HttpStatus status;
  private final String code;
  private final List<String> fields;
  private final Long currentVersion;
  private final PartnerStatus currentState;

  private PartnerProblemException(
      HttpStatus status,
      String code,
      String detail,
      List<String> fields,
      Long currentVersion,
      PartnerStatus currentState) {
    super(detail);
    this.status = status;
    this.code = code;
    this.fields = List.copyOf(fields);
    this.currentVersion = currentVersion;
    this.currentState = currentState;
  }

  public static PartnerProblemException badRequest(String code, String detail) {
    return new PartnerProblemException(HttpStatus.BAD_REQUEST, code, detail, List.of(), null, null);
  }

  public static PartnerProblemException conflict(String code, String detail) {
    return new PartnerProblemException(HttpStatus.CONFLICT, code, detail, List.of(), null, null);
  }

  public static PartnerProblemException unprocessable(
      String code, String detail, List<String> fields) {
    return new PartnerProblemException(
        HttpStatus.UNPROCESSABLE_CONTENT, code, detail, fields, null, null);
  }

  public static PartnerProblemException notFound() {
    return new PartnerProblemException(
        HttpStatus.NOT_FOUND,
        "RESOURCE_NOT_FOUND",
        "Partner was not found in the current access scope",
        List.of(),
        null,
        null);
  }

  public static PartnerProblemException preconditionRequired() {
    return new PartnerProblemException(
        HttpStatus.PRECONDITION_REQUIRED,
        "PRECONDITION_REQUIRED",
        "If-Match is required",
        List.of(),
        null,
        null);
  }

  public static PartnerProblemException versionConflict(long version, PartnerStatus state) {
    return new PartnerProblemException(
        HttpStatus.PRECONDITION_FAILED,
        "RESOURCE_VERSION_CONFLICT",
        "The partner changed after it was loaded",
        List.of(),
        version,
        state);
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public List<String> fields() {
    return fields;
  }

  public Long currentVersion() {
    return currentVersion;
  }

  public PartnerStatus currentState() {
    return currentState;
  }
}
