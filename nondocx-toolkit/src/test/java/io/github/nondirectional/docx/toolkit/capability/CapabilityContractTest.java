package io.github.nondirectional.docx.toolkit.capability;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.nondirectional.docx.toolkit.DocxToolkit;
import io.github.nondirectional.docx.toolkit.capability.model.CapabilityManifest;
import io.github.nondirectional.docx.toolkit.capability.model.ParamCapabilityDescriptor;
import io.github.nondirectional.docx.toolkit.capability.model.ToolCapabilityDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 能力契约测试（CI 强制）。参考 OfficeCLI {@code enforcement:strict} 模式：声明的能力必须与实现一致。
 *
 * <p>四项校验：
 *
 * <ol>
 *   <li>{@link #每个tooldef都有toolcapability}：收集器已强制（缺则抛异常），这里做计数断言。
 *   <li>{@link #声明的工具都有测试调用或显式豁免}：扫描测试源码，按 camelCase 方法名匹配调用点； 未覆盖且不在 {@link
 *       #TEST_COVERAGE_ALLOWLIST} 的工具失败。
 *   <li>{@link #枚举参数都声明了enumValues}：每个 type=ENUM 的参数/nested 字段必须有 enumValues。
 *   <li>{@link #digest在能力未变时稳定}：同输入同 digest（排除 generatedAt）。
 * </ol>
 */
class CapabilityContractTest {

  /** 测试覆盖豁免清单。这些工具声明了能力但暂无直接测试调用（通常是间接覆盖或待补）。 每项都应配 TODO 在后续任务补测试后移除。 */
  private static final Set<String> TEST_COVERAGE_ALLOWLIST =
      new HashSet<>(java.util.List.of("close_docx", "read_toc", "search_text"));

  private final DocxToolkit tk = new DocxToolkit();

  @Test
  void 每个_annotated_工具都有_toolcapability_且总数符合预期() {
    CapabilityManifest m = tk.capability.collectManifest();
    // 7 组文档工具（54）+ describe_capabilities 自身 = 55
    assertThat(m.tools().size()).isGreaterThanOrEqualTo(54);
    // 全部工具必须有 operation（收集器已强制 @ToolCapability 存在，这里二次确认）
    for (ToolCapabilityDescriptor t : m.tools()) {
      assertThat(t.operation()).as("%s 缺 operation", t.name()).isNotNull();
    }
  }

  @Test
  void 声明的工具都有测试调用或显式豁免() throws IOException {
    CapabilityManifest m = tk.capability.collectManifest();
    String testSources = readAllTestSources();

    List<String> uncovered = new ArrayList<>();
    for (ToolCapabilityDescriptor t : m.tools()) {
      if (TEST_COVERAGE_ALLOWLIST.contains(t.name())) {
        continue;
      }
      String camel = snakeToCamel(t.name());
      // 匹配 .methodName( 形式的调用
      Pattern p = Pattern.compile("\\." + Pattern.quote(camel) + "\\s*\\(");
      Matcher matcher = p.matcher(testSources);
      if (!matcher.find()) {
        uncovered.add(t.name() + " (-> " + camel + ")");
      }
    }
    assertThat(uncovered)
        .as(
            "以下工具声明了 @ToolCapability 但无测试调用，也未在 TEST_COVERAGE_ALLOWLIST 豁免。"
                + "请补测试或将能力标为 EXPERIMENTAL。")
        .isEmpty();
  }

  @Test
  void 枚举参数都声明了enumValues() {
    CapabilityManifest m = tk.capability.collectManifest();
    List<String> incomplete = new ArrayList<>();
    for (ToolCapabilityDescriptor t : m.tools()) {
      for (ParamCapabilityDescriptor p : t.params()) {
        if (isEnumType(p) && p.enumValues().isEmpty()) {
          incomplete.add(t.name() + "." + p.name());
        }
      }
      for (ParamCapabilityDescriptor p : t.nestedParams()) {
        if (isEnumType(p) && p.enumValues().isEmpty()) {
          incomplete.add(t.name() + "." + p.name());
        }
      }
    }
    assertThat(incomplete).as("以下 ENUM 类型参数未声明 enumValues，Agent 无法得知合法枚举值").isEmpty();
  }

  @Test
  void digest在能力未变时稳定() {
    CapabilityManifest m1 = tk.capability.collectManifest();
    tk.capability.invalidateCache();
    CapabilityManifest m2 = tk.capability.collectManifest();
    // 排除 generatedAt，digest 必须稳定
    assertThat(m1.digest()).isEqualTo(m2.digest());
  }

  @Test
  void elementIndex与工具实际元素一致() {
    CapabilityManifest m = tk.capability.collectManifest();
    // 每个 elementIndex 的工具名都必须在 tools 里存在
    Set<String> toolNames = new HashSet<>();
    for (ToolCapabilityDescriptor t : m.tools()) {
      toolNames.add(t.name());
    }
    for (var entry : m.elementIndex().entrySet()) {
      for (String name : entry.getValue()) {
        assertThat(toolNames)
            .as("elementIndex[%s] 含不存在的工具 %s", entry.getKey(), name)
            .contains(name);
      }
    }
  }

  // ===== helpers =====

  private static boolean isEnumType(ParamCapabilityDescriptor p) {
    return p.type() == ParamType.ENUM;
  }

  private static String snakeToCamel(String snake) {
    String[] parts = snake.split("_");
    StringBuilder sb = new StringBuilder(parts[0]);
    for (int i = 1; i < parts.length; i++) {
      sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
    }
    return sb.toString();
  }

  private static String readAllTestSources() throws IOException {
    StringBuilder sb = new StringBuilder();
    Path testRoot = Paths.get("src/test/java");
    if (!Files.exists(testRoot)) {
      // 兜底：从基于 surefire 的标准位置找
      testRoot = Paths.get("nondocx-toolkit/src/test/java");
    }
    java.util.stream.Stream<Path> walk = Files.walk(testRoot);
    walk.filter(p -> p.toString().endsWith(".java"))
        .forEach(
            p -> {
              try {
                sb.append(Files.readString(p, StandardCharsets.UTF_8));
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    walk.close();
    return sb.toString();
  }
}
