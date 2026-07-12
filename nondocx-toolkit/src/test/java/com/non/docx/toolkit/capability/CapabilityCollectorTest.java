package com.non.docx.toolkit.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.non.chain.tool.ToolDef;
import com.non.chain.tool.ToolParam;
import com.non.docx.toolkit.capability.model.CapabilityManifest;
import com.non.docx.toolkit.capability.model.ParamCapabilityDescriptor;
import com.non.docx.toolkit.capability.model.ToolCapabilityDescriptor;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link CapabilityCollector} 单测。用一个 fixture 工具类验证：合并 nonchain + 项目注解、校验规则、 elementIndex 聚合、digest
 * 稳定性。
 */
class CapabilityCollectorTest {

  // ===== fixture 工具类 =====

  /** 合规 fixture：两个工具，覆盖 READ/UPDATE，含 enum 与 nested 参数。 */
  @SuppressWarnings("unused")
  static final class GoodFixture {
    @ToolDef(name = "read_paragraph", description = "读取段落")
    @ToolCapability(operation = CapabilityOperation.READ, element = "paragraph")
    public String readParagraph(
        @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
            String docId,
        @ToolParam(name = "paragraph_indexes", description = "段落索引数组")
            @ParamCapability(type = ParamType.INTEGER_ARRAY)
            List<Integer> indexes) {
      return "";
    }

    @ToolDef(name = "update_paragraph_alignment", description = "修改对齐")
    @ToolCapability(
        operation = CapabilityOperation.UPDATE,
        element = "paragraph",
        examples = {"update_paragraph_alignment doc_id=X edits=[...]"})
    public String updateAlignment(
        @ToolParam(name = "doc_id", description = "文档句柄") @ParamCapability(type = ParamType.STRING)
            String docId,
        @ToolParam(name = "edits", description = "对象数组")
            @NestedParamCapability(path = "edits.paragraph_index", type = ParamType.INTEGER)
            @NestedParamCapability(
                path = "edits.alignment",
                type = ParamType.ENUM,
                enumValues = {"LEFT", "CENTER", "RIGHT", "JUSTIFY"})
            List<Map<String, Object>> edits) {
      return "";
    }
  }

  /** 缺 @ToolCapability 的 @ToolDef 方法。 */
  @SuppressWarnings("unused")
  static final class MissingToolCapabilityFixture {
    @ToolDef(name = "bad_tool", description = "缺注解")
    public String badTool(@ToolParam(name = "doc_id", description = "x") String docId) {
      return "";
    }
  }

  /** enumValues 非空但 type 非 ENUM。 */
  @SuppressWarnings("unused")
  static final class EnumMismatchFixture {
    @ToolDef(name = "bad_enum", description = "枚举不配")
    @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
    public String badEnum(
        @ToolParam(name = "align", description = "对齐")
            @ParamCapability(
                type = ParamType.STRING,
                enumValues = {"LEFT"})
            String align) {
      return "";
    }
  }

  /**
   * @ParamCapability 标在非 @ToolParam 参数上。
   */
  @SuppressWarnings("unused")
  static final class OrphanParamCapabilityFixture {
    @ToolDef(name = "orphan_pc", description = "孤儿注解")
    @ToolCapability(operation = CapabilityOperation.READ, element = "paragraph")
    public String orphan(
        @ToolParam(name = "doc_id", description = "x") String docId,
        @ParamCapability(type = ParamType.STRING) String rogue) {
      return "";
    }
  }

  /** nested path 首段与 ToolParam.name 不一致。 */
  @SuppressWarnings("unused")
  static final class BadNestedPathFixture {
    @ToolDef(name = "bad_nested", description = "嵌套路径错")
    @ToolCapability(operation = CapabilityOperation.UPDATE, element = "paragraph")
    public String badNested(
        @ToolParam(name = "edits", description = "x")
            @NestedParamCapability(
                path = "wrong.alignment",
                type = ParamType.ENUM,
                enumValues = {"LEFT"})
            List<Map<String, Object>> edits) {
      return "";
    }
  }

  // ===== 测试 =====

  @Test
  void 合并nonchain与项目注解并构建manifest() {
    CapabilityManifest m = CapabilityCollector.collect(new GoodFixture());

    assertThat(m.tools()).hasSize(2);
    assertThat(m.schemaVersion()).isEqualTo("nondocx-capability/v1");
    assertThat(m.digest()).isNotBlank();
    assertThat(m.generatedAt()).isNotBlank();

    ToolCapabilityDescriptor read = findTool(m, "read_paragraph");
    assertThat(read.operation()).isEqualTo(CapabilityOperation.READ);
    assertThat(read.element()).isEqualTo("paragraph");
    assertThat(read.level()).isEqualTo(CapabilityLevel.STABLE);
    assertThat(read.needsRecalc()).isFalse();
    assertThat(read.params()).hasSize(2);
    assertThat(read.params().get(0).name()).isEqualTo("doc_id");
    assertThat(read.params().get(0).type()).isEqualTo(ParamType.STRING);
    assertThat(read.params().get(1).name()).isEqualTo("paragraph_indexes");
    assertThat(read.params().get(1).type()).isEqualTo(ParamType.INTEGER_ARRAY);
  }

  @Test
  void 嵌套参数展开为nestedParams() {
    CapabilityManifest m = CapabilityCollector.collect(new GoodFixture());
    ToolCapabilityDescriptor update = findTool(m, "update_paragraph_alignment");

    assertThat(update.params()).hasSize(2);
    assertThat(update.params().get(1).name()).isEqualTo("edits");
    assertThat(update.nestedParams()).hasSize(2);

    ParamCapabilityDescriptor align = findByPath(update.nestedParams(), "edits.alignment");
    assertThat(align.type()).isEqualTo(ParamType.ENUM);
    assertThat(align.enumValues()).containsExactly("LEFT", "CENTER", "RIGHT", "JUSTIFY");

    ParamCapabilityDescriptor idx = findByPath(update.nestedParams(), "edits.paragraph_index");
    assertThat(idx.type()).isEqualTo(ParamType.INTEGER);
  }

  @Test
  void elementIndex按元素聚合工具名() {
    CapabilityManifest m = CapabilityCollector.collect(new GoodFixture());

    assertThat(m.elementIndex()).containsKey("paragraph");
    assertThat(m.elementIndex().get("paragraph"))
        .containsExactly("read_paragraph", "update_paragraph_alignment");
  }

  @Test
  void digest排除时间戳保持稳定() {
    CapabilityManifest m1 = CapabilityCollector.collect(new GoodFixture());
    CapabilityManifest m2 = CapabilityCollector.collect(new GoodFixture());
    // 两次收集，能力不变，digest 必须相同（尽管 generatedAt 不同）
    assertThat(m1.digest()).isEqualTo(m2.digest());
    assertThat(m1).isEqualTo(m2); // equals 基于 digest
  }

  @Test
  void 缺toolCapability抛出声明异常() {
    assertThatThrownBy(() -> CapabilityCollector.collect(new MissingToolCapabilityFixture()))
        .isInstanceOf(CapabilityDeclarationException.class)
        .hasMessageContaining("缺少 @ToolCapability")
        .hasMessageContaining("bad_tool");
  }

  @Test
  void enumValues非空但type不支持枚举值抛异常() {
    assertThatThrownBy(() -> CapabilityCollector.collect(new EnumMismatchFixture()))
        .isInstanceOf(CapabilityDeclarationException.class)
        .hasMessageContaining("enumValues 非空但 type 不支持枚举值");
  }

  @Test
  void paramCapability标在非toolParam参数抛异常() {
    assertThatThrownBy(() -> CapabilityCollector.collect(new OrphanParamCapabilityFixture()))
        .isInstanceOf(CapabilityDeclarationException.class)
        .hasMessageContaining("缺少 @ToolParam");
  }

  @Test
  void nestedPath首段不匹配抛异常() {
    assertThatThrownBy(() -> CapabilityCollector.collect(new BadNestedPathFixture()))
        .isInstanceOf(CapabilityDeclarationException.class)
        .hasMessageContaining("path 首段与 @ToolParam.name 不一致");
  }

  @Test
  void 从java类型推断paramType() {
    assertThat(CapabilityCollector.inferType(String.class)).isEqualTo(ParamType.STRING);
    assertThat(CapabilityCollector.inferType(Integer.class)).isEqualTo(ParamType.INTEGER);
    assertThat(CapabilityCollector.inferType(int.class)).isEqualTo(ParamType.INTEGER);
    assertThat(CapabilityCollector.inferType(Boolean.class)).isEqualTo(ParamType.BOOLEAN);
    assertThat(CapabilityCollector.inferType(java.util.List.class))
        .isEqualTo(ParamType.OBJECT_ARRAY);
  }

  @Test
  void 空实例列表返回空manifest() {
    CapabilityManifest m = CapabilityCollector.collect();
    assertThat(m.tools()).isEmpty();
    assertThat(m.digest()).isNotBlank(); // 空也有稳定 digest
  }

  // ===== helpers =====

  private static ToolCapabilityDescriptor findTool(CapabilityManifest m, String name) {
    return m.tools().stream()
        .filter(t -> t.name().equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到工具: " + name));
  }

  private static ParamCapabilityDescriptor findByPath(
      List<ParamCapabilityDescriptor> nested, String path) {
    return nested.stream()
        .filter(p -> p.name().equals(path))
        .findFirst()
        .orElseThrow(() -> new AssertionError("未找到 nested path: " + path));
  }
}
