package com.rom.cellarbridge.catalog.internal.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class WineProduct {

  private final UUID id;
  private final UUID producerId;
  private final UUID regionId;
  private String name;
  private final Category category;
  private String description;
  private Set<String> tags;
  private long version;

  private WineProduct(
      UUID id,
      UUID producerId,
      UUID regionId,
      String name,
      Category category,
      String description,
      Set<String> tags) {
    this.id = Objects.requireNonNull(id, "id");
    this.producerId = Objects.requireNonNull(producerId, "producerId");
    this.regionId = Objects.requireNonNull(regionId, "regionId");
    this.name = requireText(name, "name");
    this.category = Objects.requireNonNull(category, "category");
    this.description = description == null ? "" : description.strip();
    this.tags = normalizeTags(tags);
  }

  public static WineProduct create(
      UUID id,
      UUID producerId,
      UUID regionId,
      String name,
      Category category,
      String description,
      Set<String> tags) {
    return new WineProduct(id, producerId, regionId, name, category, description, tags);
  }

  public void updateContent(String name, String description, Set<String> tags) {
    this.name = requireText(name, "name");
    this.description = description == null ? "" : description.strip();
    this.tags = normalizeTags(tags);
    version++;
  }

  public UUID id() {
    return id;
  }

  public UUID producerId() {
    return producerId;
  }

  public UUID regionId() {
    return regionId;
  }

  public String name() {
    return name;
  }

  public Category category() {
    return category;
  }

  public String description() {
    return description;
  }

  public Set<String> tags() {
    return tags;
  }

  public long version() {
    return version;
  }

  private static Set<String> normalizeTags(Set<String> values) {
    if (values == null) {
      return Set.of();
    }
    return values.stream()
        .map(value -> requireText(value, "tag"))
        .map(String::strip)
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " is required");
    }
    return value.strip();
  }

  public enum Category {
    RED,
    WHITE,
    ROSE,
    SPARKLING,
    FORTIFIED,
    DESSERT,
    OTHER
  }
}
