package com.rom.cellarbridge.tradeorder.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.quotation.QuotationAcceptedV1;
import com.rom.cellarbridge.quotation.QuotationSnapshotHashV1;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.RecoverableDataAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class QuotationAcceptedEventHandlerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final String HASH = "a".repeat(64);
  private static final List<Boundary> TEXT =
      List.of(
          new Boundary("/quotationNumber", 30, false),
          new Boundary("/customer/partnerNumber", 80, false),
          new Boundary("/customer/displayName", 160, false),
          new Boundary("/route/policyVersion", 80, false),
          new Boundary("/acceptedTermsVersion", 50, false),
          new Boundary("/deliveryAddress/province", 100, false),
          new Boundary("/deliveryAddress/city", 100, false),
          new Boundary("/deliveryAddress/district", 100, true),
          new Boundary("/deliveryAddress/line1", 200, false),
          new Boundary("/deliveryAddress/postalCode", 20, true),
          new Boundary("/lines/0/skuCode", 80, false),
          new Boundary("/lines/0/description", 240, false));

  @Test
  void enforcesEveryTextBoundaryByCodePoint() throws Exception {
    for (Boundary boundary : TEXT) {
      String maximum = "x".repeat(boundary.max());
      check(true, payload -> put(payload, boundary.pointer(), maximum));
      check(false, payload -> put(payload, boundary.pointer(), maximum + "x"));
      if (boundary.optional()) {
        check(true, payload -> put(payload, boundary.pointer(), null));
        check(false, payload -> put(payload, boundary.pointer(), ""));
      }
    }
    check(true, payload -> put(payload, "/customer/displayName", "🍷".repeat(160)));
    check(false, payload -> put(payload, "/customer/displayName", "🍷".repeat(161)));
    for (String[] value :
        new String[][] {
          {"/route/code", "UNKNOWN_ROUTE"},
          {"/deliveryAddress/countryCode", "cn"},
          {"/currency", "usd"}
        }) {
      check(false, payload -> put(payload, value[0], value[1]));
    }
  }

  @Test
  void alignsWhitespaceAndWholeStringRulesWithDraft202012() throws Exception {
    JsonNode properties = schemaProperties();
    for (String whitespace : List.of(" ", "\t", "\u00a0", "\u3000")) {
      check(false, payload -> put(payload, "/quotationNumber", whitespace));
      assertThat(schemaAccepts(properties.path("quotationNumber"), whitespace)).isFalse();
    }
    for (String[] value :
        new String[][] {
          {"/currency", "USD\n"},
          {"/totalAmount", "128400.00\n"}
        }) {
      check(false, payload -> put(payload, value[0], value[1]));
      assertThat(schemaAccepts(schemaFor(properties, value[0]), value[1])).isFalse();
    }
    String hash = "a".repeat(64) + "\n";
    assertThat(schemaAccepts(properties.path("snapshotHash"), hash)).isFalse();
    assertThat(schemaAccepts(properties.path("snapshotHash"), "sha256:" + "a".repeat(64)))
        .isFalse();
    JsonNode createdHash =
        JSON.readTree(
                Files.readString(
                    contractPath("schemas", "events", "trade-order-created-v1.schema.json")))
            .at("/allOf/1/properties/payload/properties/snapshotHash");
    assertThat(schemaAccepts(createdHash, "a".repeat(64))).isTrue();
    assertThat(schemaAccepts(createdHash, "sha256:" + "a".repeat(64))).isFalse();
    assertThat(schemaAccepts(createdHash, "a".repeat(64) + "\n")).isFalse();
    assertThatThrownBy(() -> QuotationSnapshotHashV1.normalizeIncomingHash(hash))
        .isInstanceOf(QuotationSnapshotHashV1.InvalidSnapshotHashFormatException.class);
  }

  @Test
  void normalizesOnlyCurrentAndExactHistoricalHashFormats() {
    assertThat(QuotationSnapshotHashV1.normalizeIncomingHash(HASH)).isEqualTo(HASH);
    assertThat(QuotationSnapshotHashV1.normalizeStoredHash("sha256:" + HASH)).isEqualTo(HASH);
    for (String invalid :
        Arrays.asList(
            null,
            "",
            " " + HASH,
            HASH + " ",
            "sha512:" + HASH,
            "SHA256:" + HASH,
            "sha256:sha256:" + HASH,
            "a".repeat(63),
            "a".repeat(65),
            HASH.toUpperCase(),
            "sha256:" + HASH.toUpperCase())) {
      assertThatThrownBy(() -> QuotationSnapshotHashV1.normalizeIncomingHash(invalid))
          .isInstanceOf(QuotationSnapshotHashV1.InvalidSnapshotHashFormatException.class);
      assertThatThrownBy(() -> QuotationSnapshotHashV1.normalizeStoredHash(invalid))
          .isInstanceOf(QuotationSnapshotHashV1.InvalidSnapshotHashFormatException.class);
    }
  }

  @Test
  void enforcesNumericPrecisionScaleSignAndLeadingZeros() throws Exception {
    String[][] accepted = {
      {"/totalAmount", "999999999999999.9999"},
      {"/lines/0/netUnitPrice", "999999999999999.9999"},
      {"/lines/0/quantity", "9999999999999.999999"},
      {"/totalAmount", "00000000000000000000000100.0000"},
      {"/lines/0/netUnitPrice", "00000100.0000"},
      {"/lines/0/quantity", "0000001.000000"}
    };
    String[][] rejected = {
      {"/totalAmount", "1000000000000000"},
      {"/lines/0/netUnitPrice", "1000000000000000"},
      {"/lines/0/lineTotal", "1000000000000000"},
      {"/lines/0/quantity", "10000000000000"},
      {"/totalAmount", "1.00000"},
      {"/lines/0/quantity", "1.0000000"},
      {"/totalAmount", "-1"},
      {"/lines/0/netUnitPrice", "1e2"},
      {"/lines/0/quantity", "0.000000"}
    };
    for (String[] value : accepted) {
      assertValue(value, true);
    }
    for (String[] value : rejected) {
      assertValue(value, false);
    }
  }

  @Test
  void enforcesBusinessKeysLineCountsAndSum() throws Exception {
    check(false, payload -> duplicate(payload, "quotationLineId"));
    check(false, payload -> duplicate(payload, "skuId"));
    check(false, payload -> ((ArrayNode) payload.path("lines")).removeAll());
    check(true, payload -> replaceLines(payload, 50));
    check(false, payload -> replaceLines(payload, 51));
    check(false, payload -> put(payload, "/totalAmount", "1"));
  }

  @Test
  void classifiesOnlyKnownStorageFailuresAsRetryable() {
    assertClassification(
        new DataIntegrityViolationException("constraint"),
        "ORDER_PERSISTENCE_CONSTRAINT_VIOLATION",
        false);
    for (DataAccessException failure :
        List.of(
            new RecoverableDataAccessException("recoverable"),
            new DataAccessResourceFailureException("resource"),
            new CannotAcquireLockException("lock"),
            new CannotSerializeTransactionException("serialization"))) {
      assertClassification(failure, "ORDER_STORAGE_UNAVAILABLE", true);
    }
    DataAccessException unknown = new InvalidDataAccessResourceUsageException("programming");
    assertThat(QuotationAcceptedEventHandler.classifyDataAccess(unknown)).isSameAs(unknown);
  }

  private static void assertValue(String[] value, boolean valid) throws Exception {
    check(
        valid,
        payload -> {
          put(payload, value[0], value[1]);
          if (value[0].equals("/totalAmount")) {
            put(payload, "/lines/0/lineTotal", value[1]);
          }
        });
  }

  private static void check(boolean valid, Consumer<ObjectNode> mutation) throws Exception {
    ObjectNode node = payload();
    mutation.accept(node);
    QuotationAcceptedV1.Payload parsed = JSON.treeToValue(node, QuotationAcceptedV1.Payload.class);
    Runnable validation = () -> QuotationAcceptedEventHandler.validate(delivery(parsed), parsed);
    if (valid) {
      validation.run();
    } else {
      assertThatThrownBy(validation::run).isInstanceOf(RuntimeException.class);
    }
  }

  private static ObjectNode payload() throws Exception {
    return (ObjectNode)
        JSON.readTree(
                Files.readString(
                    contractPath("examples", "events", "quotation-accepted-v1.example.json")))
            .path("payload")
            .deepCopy();
  }

  private static EventDelivery delivery(QuotationAcceptedV1.Payload payload) {
    return new EventDelivery(
        UUID.randomUUID(),
        UUID.randomUUID(),
        QuotationAcceptedV1.TYPE,
        1,
        payload.acceptedAt(),
        "quotation",
        new EventDelivery.Subject("QUOTATION", payload.quotationId(), payload.quotationNumber()),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "{}");
  }

  private static void duplicate(ObjectNode payload, String field) {
    ObjectNode copy = ((ObjectNode) payload.at("/lines/0")).deepCopy();
    String other = field.equals("skuId") ? "quotationLineId" : "skuId";
    copy.put(other, UUID.randomUUID().toString());
    ((ArrayNode) payload.path("lines")).add(copy);
    payload.put("totalAmount", "256800.00");
  }

  private static void replaceLines(ObjectNode payload, int count) {
    ObjectNode line = ((ObjectNode) payload.at("/lines/0")).deepCopy();
    ArrayNode lines = ((ArrayNode) payload.path("lines")).removeAll();
    payload.put("totalAmount", "0");
    for (int index = 0; index < count; index++) {
      ObjectNode copy = line.deepCopy();
      copy.put("quotationLineId", UUID.randomUUID().toString());
      copy.put("skuId", UUID.randomUUID().toString());
      copy.put("lineTotal", "0");
      lines.add(copy);
    }
  }

  private static void assertClassification(
      DataAccessException failure, String code, boolean retry) {
    assertThat((EventHandlingException) QuotationAcceptedEventHandler.classifyDataAccess(failure))
        .returns(code, EventHandlingException::failureCode)
        .returns(retry, EventHandlingException::retryable);
  }

  private static JsonNode schemaProperties() throws Exception {
    return JSON.readTree(
            Files.readString(
                contractPath("schemas", "events", "quotation-accepted-v1.schema.json")))
        .at("/allOf/1/properties/payload/properties");
  }

  private static JsonNode schemaFor(JsonNode properties, String payloadPointer) {
    return properties.at(
        payloadPointer
            .replace("/customer/", "/customer/properties/")
            .replace("/route/", "/route/properties/")
            .replace("/deliveryAddress/", "/deliveryAddress/properties/")
            .replace("/lines/0/", "/lines/items/properties/"));
  }

  private static boolean schemaAccepts(JsonNode schema, String value) {
    return SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
        .getSchema(schema)
        .validate(JSON.valueToTree(value))
        .isEmpty();
  }

  private static void put(ObjectNode payload, String pointer, String value) {
    int separator = pointer.lastIndexOf('/');
    ObjectNode parent = (ObjectNode) payload.at(pointer.substring(0, separator));
    String field = pointer.substring(separator + 1);
    if (value == null) {
      parent.putNull(field);
    } else {
      parent.put(field, value);
    }
  }

  private static Path contractPath(String... path) {
    Path candidate = Path.of("contracts", path);
    return Files.exists(candidate) ? candidate : Path.of("..").resolve(candidate);
  }

  private record Boundary(String pointer, int max, boolean optional) {}
}
