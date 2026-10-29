package io.github.nondirectional.docx.examples.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonWriterTest {

  @Test
  void writesPrimitivesAndNull() {
    assertThat(JsonWriter.write(null)).isEqualTo("null");
    assertThat(JsonWriter.write("x")).isEqualTo("\"x\"");
    assertThat(JsonWriter.write(42)).isEqualTo("42");
    assertThat(JsonWriter.write(3.5)).isEqualTo("3.5");
    assertThat(JsonWriter.write(true)).isEqualTo("true");
    assertThat(JsonWriter.write(Double.NaN)).isEqualTo("null");
  }

  @Test
  void escapesQuotesBackslashAndNewline() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("text", "a\"b\\c\nd");
    assertThat(JsonWriter.write(map)).isEqualTo("{\"text\":\"a\\\"b\\\\c\\nd\"}");
  }

  @Test
  void escapesControlCharacters() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("control", "a" + (char) 1 + "b");
    assertThat(JsonWriter.write(map)).contains("\"control\":\"a\\u0001b\"");
  }

  @Test
  void writesNestedStructures() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("list", List.of(1, "x", true));
    map.put("nested", Map.of("k", "v"));
    assertThat(JsonWriter.write(map))
        .isEqualTo("{\"list\":[1,\"x\",true],\"nested\":{\"k\":\"v\"}}");
  }

  @Test
  void stringifiesUnknownValuesInsteadOfFailing() {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put(
        "obj",
        new Object() {
          @Override
          public String toString() {
            return "custom";
          }
        });
    assertThat(JsonWriter.write(map)).isEqualTo("{\"obj\":\"custom\"}");
  }
}
