package io.github.nondirectional.docx.examples.eval;

import java.util.Map;

/**
 * 最小 JSON 序列化器。
 *
 * <p>评测报告只包含 Map / List / String / Number / Boolean / null，没必要为它引入 Jackson。
 * 手写实现集中处理字符串转义，单测覆盖引号、反斜杠、换行、控制字符和嵌套结构。
 *
 * <p>输出为紧凑 JSON（无缩进）；人可读版本由 {@link ReportWriter} 生成 Markdown。
 */
public final class JsonWriter {

  private JsonWriter() {}

  /** 序列化为 JSON 字符串。 */
  public static String write(Object value) {
    StringBuilder sb = new StringBuilder();
    append(value, sb);
    return sb.toString();
  }

  private static void append(Object value, StringBuilder sb) {
    if (value == null) {
      sb.append("null");
      return;
    }
    if (value instanceof String) {
      appendString((String) value, sb);
      return;
    }
    if (value instanceof Boolean) {
      sb.append(value);
      return;
    }
    if (value instanceof Number) {
      Number number = (Number) value;
      if (number instanceof Double || number instanceof Float) {
        double d = number.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) {
          sb.append("null");
        } else {
          sb.append(number);
        }
      } else {
        sb.append(number);
      }
      return;
    }
    if (value instanceof Map) {
      appendMap((Map<?, ?>) value, sb);
      return;
    }
    if (value instanceof Iterable) {
      appendIterable((Iterable<?>) value, sb);
      return;
    }
    // 兜底：未知类型按字符串输出，保证报告不会因为一个字段而整体失败。
    appendString(String.valueOf(value), sb);
  }

  private static void appendMap(Map<?, ?> map, StringBuilder sb) {
    sb.append('{');
    boolean first = true;
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      appendString(String.valueOf(entry.getKey()), sb);
      sb.append(':');
      append(entry.getValue(), sb);
    }
    sb.append('}');
  }

  private static void appendIterable(Iterable<?> values, StringBuilder sb) {
    sb.append('[');
    boolean first = true;
    for (Object value : values) {
      if (!first) {
        sb.append(',');
      }
      first = false;
      append(value, sb);
    }
    sb.append(']');
  }

  private static void appendString(String value, StringBuilder sb) {
    sb.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"':
          sb.append("\\\"");
          break;
        case '\\':
          sb.append("\\\\");
          break;
        case '\b':
          sb.append("\\b");
          break;
        case '\f':
          sb.append("\\f");
          break;
        case '\n':
          sb.append("\\n");
          break;
        case '\r':
          sb.append("\\r");
          break;
        case '\t':
          sb.append("\\t");
          break;
        default:
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
      }
    }
    sb.append('"');
  }
}
