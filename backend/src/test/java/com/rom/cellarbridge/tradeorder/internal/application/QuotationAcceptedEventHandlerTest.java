package com.rom.cellarbridge.tradeorder.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.rom.cellarbridge.platform.EventDelivery;
import com.rom.cellarbridge.platform.EventHandlingException;
import com.rom.cellarbridge.quotation.QuotationAcceptedV1;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.InvalidDataAccessResourceUsageException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class QuotationAcceptedEventHandlerTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final List<TextBoundary> TEXT_BOUNDARIES =
      List.of(
          new TextBoundary("/quotationNumber", "/quotationNumber", 30, false),
          new TextBoundary(
              "/customer/partnerNumber", "/customer/properties/partnerNumber", 80, false),
          new TextBoundary("/customer/displayName", "/customer/properties/displayName", 160, false),
          new TextBoundary("/route/policyVersion", "/route/properties/policyVersion", 80, false),
          new TextBoundary("/acceptedTermsVersion", "/acceptedTermsVersion", 50, false),
          new TextBoundary(
              "/deliveryAddress/province", "/deliveryAddress/properties/province", 100, false),
          new TextBoundary("/deliveryAddress/city", "/deliveryAddress/properties/city", 100, false),
          new TextBoundary(
              "/deliveryAddress/district", "/deliveryAddress/properties/district", 100, true),
          new TextBoundary(
              "/deliveryAddress/line1", "/deliveryAddress/properties/line1", 200, false),
          new TextBoundary(
              "/deliveryAddress/postalCode", "/deliveryAddress/properties/postalCode", 20, true),
          new TextBoundary("/lines/0/skuCode", "/lines/items/properties/skuCode", 80, false),
          new TextBoundary(
              "/lines/0/description", "/lines/items/properties/description", 240, false));

  @Test
  void enforcesEveryTextAndAddressBoundaryByCodePoint() throws Exception {
    for (TextBoundary boundary : TEXT_BOUNDARIES) {
      assertValid(payload -> put(payload, boundary.payloadPointer(), "x".repeat(boundary.max())));
      assertInvalid(
          payload -> put(payload, boundary.payloadPointer(), "x".repeat(boundary.max() + 1)));
      if (boundary.optional()) {
        assertValid(payload -> putNull(payload, boundary.payloadPointer()));
        assertInvalid(payload -> put(payload, boundary.payloadPointer(), ""));
      }
    }
    assertValid(payload -> put(payload, "/customer/displayName", "🍷".repeat(160)));
    assertInvalid(payload -> put(payload, "/customer/displayName", "🍷".repeat(161)));
    assertInvalid(payload -> put(payload, "/route/code", "UNKNOWN_ROUTE"));
    assertInvalid(payload -> put(payload, "/deliveryAddress/countryCode", "cn"));
  }

  @Test
  void enforcesNumericPrecisionScaleSignAndLeadingZeroRules() throws Exception {
    assertValid(payload -> amount(payload, "999999999999999.9999"));
    assertValid(payload -> put(payload, "/lines/0/netUnitPrice", "999999999999999.9999"));
    assertValid(payload -> put(payload, "/lines/0/quantity", "9999999999999.999999"));
    assertValid(
        payload -> {
          amount(payload, "00000000000000000000000100.0000");
          put(payload, "/lines/0/netUnitPrice", "00000100.0000");
          put(payload, "/lines/0/quantity", "0000001.000000");
        });
    for (Consumer<ObjectNode> invalid :
        List.<Consumer<ObjectNode>>of(
            payload -> put(payload, "/totalAmount", "1000000000000000"),
            payload -> put(payload, "/lines/0/netUnitPrice", "1000000000000000"),
            payload -> put(payload, "/lines/0/lineTotal", "1000000000000000"),
            payload -> put(payload, "/lines/0/quantity", "10000000000000"),
            payload -> put(payload, "/totalAmount", "1.00000"),
            payload -> put(payload, "/lines/0/quantity", "1.0000000"),
            payload -> put(payload, "/totalAmount", "-1"),
            payload -> put(payload, "/lines/0/netUnitPrice", "1e2"),
            payload -> put(payload, "/lines/0/quantity", "0.000000"))) {
      assertInvalid(invalid);
    }
  }

  @Test
  void rejectsDuplicateBusinessKeysAndInvalidLineCounts() throws Exception {
    assertInvalid(
        payload -> {
          ObjectNode duplicate = ((ObjectNode) payload.at("/lines/0")).deepCopy();
          duplicate.put("quotationLineId", UUID.randomUUID().toString());
          ((ArrayNode) payload.path("lines")).add(duplicate);
          payload.put("totalAmount", "256800.00");
        });
    assertInvalid(
        payload -> {
          ObjectNode duplicate = ((ObjectNode) payload.at("/lines/0")).deepCopy();
          duplicate.put("skuId", UUID.randomUUID().toString());
          ((ArrayNode) payload.path("lines")).add(duplicate);
          payload.put("totalAmount", "256800.00");
        });
    assertInvalid(payload -> ((ArrayNode) payload.path("lines")).removeAll());
    assertInvalid(
        payload -> {
          ObjectNode line = ((ObjectNode) payload.at("/lines/0")).deepCopy();
          ArrayNode lines = ((ArrayNode) payload.path("lines")).removeAll();
          payload.put("totalAmount", "0");
          for (int index = 0; index < 51; index++) {
            ObjectNode copy = line.deepCopy();
            copy.put("quotationLineId", UUID.randomUUID().toString());
            copy.put("skuId", UUID.randomUUID().toString());
            copy.put("lineTotal", "0");
            lines.add(copy);
          }
        });
  }

  @Test
  void schemaExpressesTheSameDeterministicBounds() throws Exception {
    JsonNode properties =
        JSON.readTree(
                Files.readString(
                    contractPath("schemas", "events", "quotation-accepted-v1.schema.json")))
            .at("/allOf/1/properties/payload/properties");
    for (TextBoundary boundary : TEXT_BOUNDARIES) {
      JsonNode property = properties.at(boundary.schemaPointer());
      assertThat(property.path("minLength").asInt()).isEqualTo(1);
      assertThat(property.path("maxLength").asInt()).isEqualTo(boundary.max());
      assertThat(Pattern.compile(property.path("pattern").asText()).matcher(" ").find()).isFalse();
    }
    assertThat(properties.path("revision").path("maximum").asInt()).isEqualTo(Integer.MAX_VALUE);
    assertThat(properties.at("/customer/properties/sourceVersion/maximum").asInt())
        .isEqualTo(Integer.MAX_VALUE);
    assertThat(properties.path("lines").path("maxItems").asInt()).isEqualTo(50);
    Pattern amount = Pattern.compile(properties.path("totalAmount").path("pattern").asText());
    Pattern quantity =
        Pattern.compile(properties.at("/lines/items/properties/quantity/pattern").asText());
    assertThat(amount.matcher("000999999999999999.9999").matches()).isTrue();
    assertThat(amount.matcher("1000000000000000").matches()).isFalse();
    assertThat(quantity.matcher("0009999999999999.999999").matches()).isTrue();
    assertThat(quantity.matcher("0.000000").matches()).isFalse();
    assertThat(quantity.matcher("10000000000000").matches()).isFalse();
  }

  @Test
  void classifiesOnlyKnownStorageFailuresAsRetryable() {
    EventHandlingException constraint =
        (EventHandlingException)
            QuotationAcceptedEventHandler.classifyDataAccess(
                new DataIntegrityViolationException("constraint"));
    assertThat(constraint.failureCode()).isEqualTo("ORDER_PERSISTENCE_CONSTRAINT_VIOLATION");
    assertThat(constraint.retryable()).isFalse();

    for (DataAccessException transientFailure :
        List.of(
            new TransientDataAccessResourceException("transient"),
            new RecoverableDataAccessException("recoverable"),
            new DataAccessResourceFailureException("resource"),
            new CannotAcquireLockException("lock"),
            new CannotSerializeTransactionException("serialization"),
            new QueryTimeoutException("timeout"))) {
      EventHandlingException classified =
          (EventHandlingException)
              QuotationAcceptedEventHandler.classifyDataAccess(transientFailure);
      assertThat(classified.failureCode()).isEqualTo("ORDER_STORAGE_UNAVAILABLE");
      assertThat(classified.retryable()).isTrue();
    }
    DataAccessException unknown = new InvalidDataAccessResourceUsageException("programming");
    assertThat(QuotationAcceptedEventHandler.classifyDataAccess(unknown)).isSameAs(unknown);
  }

  private static void assertValid(Consumer<ObjectNode> mutation) throws Exception {
    ObjectNode payload = payload();
    mutation.accept(payload);
    QuotationAcceptedV1.Payload parsed = parsed(payload);
    assertThatCode(() -> QuotationAcceptedEventHandler.validate(delivery(parsed), parsed))
        .doesNotThrowAnyException();
  }

  private static void assertInvalid(Consumer<ObjectNode> mutation) throws Exception {
    ObjectNode payload = payload();
    mutation.accept(payload);
    QuotationAcceptedV1.Payload parsed = parsed(payload);
    assertThatThrownBy(() -> QuotationAcceptedEventHandler.validate(delivery(parsed), parsed))
        .isInstanceOf(RuntimeException.class);
  }

  private static ObjectNode payload() throws Exception {
    return (ObjectNode)
        JSON.readTree(
                Files.readString(
                    contractPath("examples", "events", "quotation-accepted-v1.example.json")))
            .path("payload")
            .deepCopy();
  }

  private static QuotationAcceptedV1.Payload parsed(ObjectNode payload) throws Exception {
    return JSON.treeToValue(payload, QuotationAcceptedV1.Payload.class);
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

  private static void amount(ObjectNode payload, String value) {
    payload.put("totalAmount", value);
    put(payload, "/lines/0/lineTotal", value);
  }

  private static void put(ObjectNode payload, String pointer, String value) {
    int separator = pointer.lastIndexOf('/');
    ((ObjectNode) payload.at(pointer.substring(0, separator)))
        .put(pointer.substring(separator + 1), value);
  }

  private static void putNull(ObjectNode payload, String pointer) {
    int separator = pointer.lastIndexOf('/');
    ((ObjectNode) payload.at(pointer.substring(0, separator)))
        .putNull(pointer.substring(separator + 1));
  }

  private static Path contractPath(String... path) {
    Path candidate = Path.of("contracts", path);
    return Files.exists(candidate) ? candidate : Path.of("..").resolve(candidate);
  }

  private record TextBoundary(
      String payloadPointer, String schemaPointer, int max, boolean optional) {}
}
