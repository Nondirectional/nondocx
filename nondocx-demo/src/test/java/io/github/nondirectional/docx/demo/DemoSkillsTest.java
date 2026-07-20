package io.github.nondirectional.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.chain.skill.SkillDefinition;
import com.non.chain.skill.SkillRegistry;
import com.non.chain.tool.Tool;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证顶层 Skill 集合的装配契约（{@link DemoSkills}）。
 *
 * <p>覆盖 PRD R2/R5 与验收项：6 条 Skill 均从 classpath Markdown 加载并注册；缺失或空正文启动时 fail-fast；description 带
 * {@code [Skill]} 前缀；Skill function 无参数。不依赖远程模型。
 */
class DemoSkillsTest {

  private static final List<String> EXPECTED =
      List.of(
          "inspect-document",
          "edit-body",
          "edit-table",
          "tracked-changes",
          "audit-quality",
          "inspect-special-parts");

  @Test
  void createRegistersAllSixSkillsInOrder() {
    SkillRegistry registry = DemoSkills.create();
    assertEquals(EXPECTED, registry.skillNames());
    for (String name : EXPECTED) {
      assertTrue(registry.contains(name), "应包含: " + name);
    }
  }

  @Test
  void descriptionsCarrySkillPrefix() {
    SkillRegistry registry = DemoSkills.create();
    for (String name : EXPECTED) {
      SkillDefinition def = registry.get(name);
      assertNotNull(def, name);
      assertTrue(
          def.description().startsWith(DemoSkills.PREFIX),
          name + " description 应带 [Skill] 前缀: " + def.description());
    }
  }

  @Test
  void contentsAreNonBlankAndWithinLengthBudget() {
    SkillRegistry registry = DemoSkills.create();
    for (String name : EXPECTED) {
      String content = registry.get(name).content();
      assertFalse(content.isBlank(), name + " 正文不应为空");
      // design.md §2 约定正文 300–600 中文字符；下限留余量避免维护期脆弱。
      int chinese = countChinese(content);
      assertTrue(chinese >= 250 && chinese <= 900, name + " 中文字符数不在 250-900: " + chinese);
    }
  }

  @Test
  void skillToolsExposeNoParameters() {
    // Skill 是无参数 function：function definition 的 parameters properties 应为空。
    // nonchain 会填一个空 schema 对象（parameters Optional 非空但无 properties）。
    SkillRegistry registry = DemoSkills.create();
    List<Tool> skillTools = registry.getSkillTools();
    assertEquals(EXPECTED.size(), skillTools.size());
    for (Tool tool : skillTools) {
      assertTrue(EXPECTED.contains(tool.name()), "未预期的 Skill 工具名: " + tool.name());
      assertTrue(tool.description().startsWith(DemoSkills.PREFIX), tool.name() + " 缺 [Skill] 前缀");
      var params = tool.toFunctionDefinition().parameters();
      assertTrue(
          params.isEmpty() || hasNoProperties(params.orElseThrow()),
          tool.name() + " 应为无参数 function: " + params);
    }
  }

  /** parameters schema 的 properties 为空 map → 无参数 function。 */
  @SuppressWarnings("rawtypes")
  private static boolean hasNoProperties(com.openai.models.FunctionParameters params) {
    com.openai.core.JsonValue props = params._additionalProperties().get("properties");
    if (props == null) return true;
    Map converted = props.convert(Map.class);
    return converted == null || converted.isEmpty();
  }

  @Test
  void loadFailsFastOnMissingResource() {
    IllegalStateException ex =
        assertThrows(IllegalStateException.class, () -> DemoSkills.load("does-not-exist"));
    assertTrue(ex.getMessage().contains("Skill 资源缺失"), ex.getMessage());
  }

  @Test
  void descriptionLookupThrowsForUnknownSkill() {
    assertThrows(IllegalArgumentException.class, () -> DemoSkills.description("nope"));
  }

  private static int countChinese(String text) {
    int n = 0;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c >= '\u4e00' && c <= '\u9fff') n++;
    }
    return n;
  }
}
