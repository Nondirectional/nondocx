package com.non.docx.toolkit.capability;

import static org.assertj.core.api.Assertions.assertThat;

import com.non.docx.toolkit.DocxToolkit;
import com.non.docx.toolkit.capability.model.CapabilityManifest;
import com.non.docx.toolkit.result.ToolResultCode;
import com.non.docx.toolkit.result.ToolResultParser;
import org.junit.jupiter.api.Test;

/** {@link CapabilityTools#describeCapabilities} 单测。 */
class CapabilityToolsTest {

  private final DocxToolkit tk = new DocxToolkit();

  @Test
  void 无过滤返回全部工具() {
    String out = tk.capability.describeCapabilities(null, null, null);
    ToolResultParser.Snapshot snap = ToolResultParser.parse(out);
    assertThat(snap.success()).isTrue();
    assertThat(snap.code()).isEqualTo(ToolResultCode.OK);
    // data 文本里应含工具总数
    assertThat(out).contains("toolCount");
  }

  @Test
  void 按元素过滤paragraph() {
    String out = tk.capability.describeCapabilities("paragraph", null, null);
    ToolResultParser.Snapshot snap = ToolResultParser.parse(out);
    assertThat(snap.success()).isTrue();
    // 过滤后全部工具都应是 paragraph 元素
    assertThat(out).contains("\"element\":\"paragraph\"");
    assertThat(out).doesNotContain("\"element\":\"table\"");
  }

  @Test
  void 按元素过滤大小写不敏感() {
    String out = tk.capability.describeCapabilities("TABLE", null, null);
    assertThat(out).contains("\"element\":\"table\"");
  }

  @Test
  void 按操作过滤read() {
    String out = tk.capability.describeCapabilities(null, "READ", null);
    ToolResultParser.Snapshot snap = ToolResultParser.parse(out);
    assertThat(snap.success()).isTrue();
    assertThat(out).contains("\"operation\":\"read\"");
    assertThat(out).doesNotContain("\"operation\":\"update\"");
  }

  @Test
  void 按操作过滤小写read也生效() {
    String out = tk.capability.describeCapabilities(null, "read", null);
    assertThat(out).contains("\"operation\":\"read\"");
  }

  @Test
  void 按稳定性过滤默认stable() {
    String out = tk.capability.describeCapabilities(null, null, "STABLE");
    ToolResultParser.Snapshot snap = ToolResultParser.parse(out);
    assertThat(snap.success()).isTrue();
    // 全部工具默认 STABLE，过滤后应基本等于全部
    assertThat(out).contains("\"level\":\"stable\"");
  }

  @Test
  void 非法operation返回错误码() {
    String out = tk.capability.describeCapabilities(null, "BOGUS", null);
    ToolResultParser.Snapshot snap = ToolResultParser.parse(out);
    assertThat(snap.success()).isFalse();
    assertThat(snap.code()).isEqualTo(ToolResultCode.INVALID_ARGUMENT);
    assertThat(out).contains("READ/ADD/UPDATE/REMOVE/QUERY/SESSION/QUALITY");
  }

  @Test
  void 非法level返回错误码() {
    String out = tk.capability.describeCapabilities(null, null, "BOGUS");
    ToolResultParser.Snapshot snap = ToolResultParser.parse(out);
    assertThat(snap.success()).isFalse();
    assertThat(snap.code()).isEqualTo(ToolResultCode.INVALID_ARGUMENT);
  }

  @Test
  void manifest包含describeCapabilities自身() {
    CapabilityManifest m = tk.capability.collectManifest();
    assertThat(m.tools())
        .anySatisfy(
            t -> {
              assertThat(t.name()).isEqualTo("describe_capabilities");
              assertThat(t.operation()).isEqualTo(CapabilityOperation.QUERY);
            });
  }

  @Test
  void manifest覆盖全部文档工具() {
    CapabilityManifest m = tk.capability.collectManifest();
    // 7 组文档工具（54）+ describe_capabilities 自身
    assertThat(m.tools().size()).isGreaterThanOrEqualTo(54);
    assertThat(m.digest()).isNotBlank();
    // elementIndex 应含主要元素
    assertThat(m.elementIndex())
        .containsKeys("paragraph", "run", "table", "cell", "tracked_change");
  }

  @Test
  void describeCapabilities自身也参与manifest收集无循环() {
    // 调用多次确认无 StackOverflowError
    tk.capability.collectManifest();
    tk.capability.collectManifest();
    tk.capability.describeCapabilities(null, null, null);
  }
}
