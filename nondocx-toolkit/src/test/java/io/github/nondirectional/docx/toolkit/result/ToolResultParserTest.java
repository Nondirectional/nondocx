package io.github.nondirectional.docx.toolkit.result;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link ToolResultParser} 双模式解析测试。 */
class ToolResultParserTest {

  @Test
  void parsesStructuredSuccessEnvelope() {
    String output =
        "已打开文档\n" + "```json\n" + "{\"success\":true,\"code\":\"ok\",\"data\":\"doc-1\"}\n" + "```";

    ToolResultParser.Snapshot snap = ToolResultParser.parse(output);

    assertThat(snap).isNotNull();
    assertThat(snap.success()).isTrue();
    assertThat(snap.code()).isEqualTo(ToolResultCode.OK);
    assertThat(snap.isFailure()).isFalse();
  }

  @Test
  void parsesStructuredFailureWithSuggestion() {
    String output =
        "run 索引 5 越界（共 2）[index_out_of_range]\n"
            + "```json\n"
            + "{\"success\":false,\"code\":\"index_out_of_range\",\"message\":\"run 索引 5 越界\","
            + "\"suggestion\":\"使用 0..1\"}\n"
            + "```";

    ToolResultParser.Snapshot snap = ToolResultParser.parse(output);

    assertThat(snap).isNotNull();
    assertThat(snap.success()).isFalse();
    assertThat(snap.isFailure()).isTrue();
    assertThat(snap.code()).isEqualTo(ToolResultCode.INDEX_OUT_OF_RANGE);
    assertThat(snap.suggestion()).isEqualTo("使用 0..1");
  }

  @Test
  void returnsNullForLegacyChineseOnlyOutput() {
    String legacy = "错误：无法打开文档 test.docx（IO 异常）";

    ToolResultParser.Snapshot snap = ToolResultParser.parse(legacy);

    assertThat(snap).isNull();
  }

  @Test
  void parseSuccessReturnsNullForLegacy() {
    assertThat(ToolResultParser.parseSuccess("错误：xxx")).isNull();
  }

  @Test
  void parseSuccessReturnsTrueForStructured() {
    String output = "成功\n```json\n{\"success\":true,\"code\":\"ok\"}\n```";
    assertThat(ToolResultParser.parseSuccess(output)).isTrue();
  }

  @Test
  void parseCodeReturnsCodeForStructured() {
    String output = "失败\n```json\n{\"success\":false,\"code\":\"element_removed\"}\n```";
    assertThat(ToolResultParser.parseCode(output)).isEqualTo(ToolResultCode.ELEMENT_REMOVED);
  }

  @Test
  void returnsNullForNullInput() {
    assertThat(ToolResultParser.parse(null)).isNull();
    assertThat(ToolResultParser.parse("")).isNull();
  }

  @Test
  void returnsNullForMalformedJson() {
    String output = "消息\n```json\n{这不是合法json}\n```";
    assertThat(ToolResultParser.parse(output)).isNull();
  }

  @Test
  void hasStructuredEnvelopeDetectsFence() {
    assertThat(ToolResultParser.hasStructuredEnvelope("x\n```json\n{}\n```")).isTrue();
    assertThat(ToolResultParser.hasStructuredEnvelope("纯中文错误")).isFalse();
  }

  @Test
  void roundTripRenderThenParsePreservesSuccessAndCode() {
    ToolResult<String> original = ToolResult.ok("data", "操作完成", 3, java.util.List.of("ref-1"));

    String rendered = ToolResultRenderer.render(original);
    ToolResultParser.Snapshot snap = ToolResultParser.parse(rendered);

    assertThat(snap).isNotNull();
    assertThat(snap.success()).isTrue();
    assertThat(snap.code()).isEqualTo(ToolResultCode.OK);
  }

  @Test
  void roundTripFailurePreservesCodeAndSuggestion() {
    ToolResult<Void> original = ToolResult.fail(ToolResultCode.STALE_REF, "引用失效", "重新读取文档");

    String rendered = ToolResultRenderer.render(original);
    ToolResultParser.Snapshot snap = ToolResultParser.parse(rendered);

    assertThat(snap.code()).isEqualTo(ToolResultCode.STALE_REF);
    assertThat(snap.suggestion()).isEqualTo("重新读取文档");
  }
}
