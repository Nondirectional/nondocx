package io.github.nondirectional.docx.toolkit.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link ToolResultRenderer} 双段序列化测试。 */
class ToolResultRendererTest {

  @Test
  void successRendersChineseMessageAndJsonFence() {
    ToolResult<String> result =
        ToolResult.ok("doc-1", "已打开文档", 1, List.of("doc:x@g1/paragraph:session:p-0"));

    String rendered = ToolResultRenderer.render(result);

    assertThat(rendered).startsWith("已打开文档");
    assertThat(rendered).contains(ToolResultRenderer.JSON_FENCE_START);
    assertThat(rendered).contains(ToolResultRenderer.JSON_FENCE_END);
    assertThat(rendered).contains("\"success\":true");
    assertThat(rendered).contains("\"code\":\"ok\"");
    assertThat(rendered).contains("\"data\":\"doc-1\"");
    assertThat(rendered).contains("\"matchedCount\":1");
    assertThat(rendered).contains("\"changedRefs\"");
  }

  @Test
  void failureAppendsCodeBracketAndSuggestion() {
    ToolResult<Void> result =
        ToolResult.fail(ToolResultCode.INDEX_OUT_OF_RANGE, "run 索引 5 越界（共 2）", "使用 0..1");

    String rendered = ToolResultRenderer.render(result);

    assertThat(rendered).startsWith("run 索引 5 越界（共 2）[index_out_of_range]");
    assertThat(rendered).contains("\"success\":false");
    assertThat(rendered).contains("\"code\":\"index_out_of_range\"");
    assertThat(rendered).contains("\"suggestion\":\"使用 0..1\"");
  }

  @Test
  void warningsSerializedAsArray() {
    ToolResult<Void> result =
        ToolResult.<Void>partial(
                ToolResultCode.PARTIAL_FAILURE,
                null,
                "批量完成：2 成功，1 失败",
                List.of(ToolWarning.of("compatibility", "段落 3 疑似样式漂移", "doc:x@p-3")))
            .withWarning(ToolWarning.of("size", "图片过大"));

    String json = ToolResultRenderer.renderJson(result);

    assertThat(json).contains("\"warnings\"");
    assertThat(json).contains("\"compatibility\"");
    assertThat(json).contains("\"段落 3 疑似样式漂移\"");
  }

  @Test
  void nullDataOmitsDataField() {
    ToolResult<Void> result = ToolResult.ok("操作成功");

    String json = ToolResultRenderer.renderJson(result);

    assertThat(json).doesNotContain("\"data\"");
  }

  @Test
  void renderNullResultReturnsLiteralNull() {
    assertThat(ToolResultRenderer.render(null)).isEqualTo("null");
  }

  @Test
  void nonSerializableDataFallsBackWithoutException() {
    // 循环引用导致 Jackson 序列化失败
    SelfRef data = new SelfRef();
    data.self = data;
    ToolResult<Object> result = ToolResult.ok(data, "测试");

    String json = ToolResultRenderer.renderJson(result);

    assertThat(json).contains("\"success\":true");
    assertThat(json).contains("__serializeError");
  }

  @Test
  void toStringUsesRenderer() {
    ToolResult<String> result = ToolResult.ok("data", "成功");

    assertThat(result.toString()).isEqualTo(ToolResultRenderer.render(result));
  }

  /** 自引用循环，Jackson 序列化会抛异常。 */
  static class SelfRef {
    public SelfRef self;
  }
}
