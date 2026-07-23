package com.rom.cellarbridge.catalog;

public final class CatalogQueryException extends RuntimeException {

  private final Code code;

  private CatalogQueryException(Code code, String message) {
    super(message);
    this.code = code;
  }

  public static CatalogQueryException invalidRequest(String message) {
    return new CatalogQueryException(Code.INVALID_REQUEST, message);
  }

  public static CatalogQueryException notFound() {
    return new CatalogQueryException(Code.NOT_FOUND, "SKU was not found in the current tenant");
  }

  public static CatalogQueryException resultTooLarge() {
    return new CatalogQueryException(Code.RESULT_TOO_LARGE, "Catalog result exceeds its budget");
  }

  public Code code() {
    return code;
  }

  public enum Code {
    INVALID_REQUEST,
    NOT_FOUND,
    RESULT_TOO_LARGE
  }
}
