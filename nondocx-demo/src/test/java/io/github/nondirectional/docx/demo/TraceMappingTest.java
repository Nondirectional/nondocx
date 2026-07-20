package io.github.nondirectional.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 Skill 激活的 trace 帧映射契约（{@link AgentBridge#skillActivatedFields}）。
 *
 * <p>覆盖 PRD R4 与验收项：帧字段完整（event/turnId/agent/skill/description/contentLength 中的 Skill 专属部分）；Skill
 * 正文不进入 SSE/JSONL；description 含 {@code [Skill]} 前缀。
 *
 * <p>这是 design.md §6.3 抽出的 package-private 纯函数测试，避免直接构造 Javalin Context。
 */
class TraceMappingTest {

  @Test
  void skillFieldsContainAllContractKeys() {
    Map<String, Object> fields = AgentBridge.skillActivatedFields("edit-table", 428);
    assertEquals("skill_activated", fields.get("event"));
    assertEquals("edit-table", fields.get("skill"));
    assertEquals(428, fields.get("contentLength"));
    assertTrue(
        ((String) fields.get("description")).startsWith(DemoSkills.PREFIX),
        "description 应带 [Skill] 前缀: " + fields.get("description"));
  }

  @Test
  void skillFieldsDoNotLeakContentBody() {
    // 关键契约：SSE/JSONL 不含 Skill 正文，只暴露名称/description/字符数。
    Map<String, Object> fields = AgentBridge.skillActivatedFields("audit-quality", 350);
    String body = DemoSkills.load("audit-quality");
    for (Map.Entry<String, Object> e : fields.entrySet()) {
      Object value = e.getValue();
      if (value instanceof String) {
        assertFalse(((String) value).contains(body), "帧字段 " + e.getKey() + " 不应泄漏 Skill 正文");
      }
    }
    // 仅暴露字符数而非正文本身。
    assertEquals(350, fields.get("contentLength"));
  }

  @Test
  void unknownSkillDoesNotCrashMapping() {
    // 未知 Skill 名称（不应发生）回退为名称字符串，不抛异常、不中断 trace 流。
    Map<String, Object> fields = AgentBridge.skillActivatedFields("mystery-skill", 10);
    assertEquals("mystery-skill", fields.get("skill"));
    assertEquals("mystery-skill", fields.get("description"));
    assertEquals(10, fields.get("contentLength"));
  }
}
