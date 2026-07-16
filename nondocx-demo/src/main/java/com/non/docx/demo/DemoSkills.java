package com.non.docx.demo;

import com.non.chain.skill.SkillRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Demo 的顶层 Skill 集合装配。
 *
 * <p>Skill 是无参数 function，由 LLM 按意图自主点选；正文作为 SYSTEM 过程知识注入， 不改变普通工具的 dirty / 质检 / 保存语义（见 {@code
 * agent-single.md}）。
 *
 * <p>本类只负责元数据注册与 classpath 正文加载，不解析 Markdown front matter—— Skill 的 name/description
 * 由本类显式声明，避免引入额外解析依赖。
 *
 * <p>加载失败（资源缺失或正文为空）直接抛 {@link IllegalStateException}，让 Demo 在启动时 fail-fast，而非运行期静默丢失 Skill。
 */
final class DemoSkills {

  /** description 统一前缀，使 LLM function 列表里 Skill 可被识别。 */
  static final String PREFIX = "[Skill] ";

  /** 固定注册顺序；LinkedHashMap 保序便于稳定测试与日志。 */
  private static final Map<String, String> SKILL_DESCRIPTIONS = new LinkedHashMap<>();

  static {
    SKILL_DESCRIPTIONS.put("inspect-document", PREFIX + "文档内容、结构和目标定位的只读分析。");
    SKILL_DESCRIPTIONS.put("edit-body", PREFIX + "正文段落、run、样式和超链接编辑。");
    SKILL_DESCRIPTIONS.put("edit-table", PREFIX + "表格创建、单元格编辑、合并和相关布局处理。");
    SKILL_DESCRIPTIONS.put("tracked-changes", PREFIX + "修订查询、接受/拒绝和修订创作。");
    SKILL_DESCRIPTIONS.put("audit-quality", PREFIX + "质量检查、问题解释，以及用户明确要求时的修复与复检。");
    SKILL_DESCRIPTIONS.put("inspect-special-parts", PREFIX + "页眉、页脚和目录的只读检查。");
  }

  private DemoSkills() {}

  /**
   * 创建已注册全部顶层 Skill 的 {@link SkillRegistry}。
   *
   * <p>每条 Skill 的 name/description 来自 {@link #SKILL_DESCRIPTIONS}，正文从 classpath {@code
   * /skills/<name>.md} 读取。任一资源缺失或正文 trim 后为空都会抛 {@link IllegalStateException}。
   *
   * @return 已注册 6 条 Skill 的 registry
   */
  static SkillRegistry create() {
    SkillRegistry registry = new SkillRegistry();
    for (Map.Entry<String, String> entry : SKILL_DESCRIPTIONS.entrySet()) {
      String name = entry.getKey();
      String description = entry.getValue();
      String content = load(name);
      registry.register(name, description).content(content).build();
    }
    return registry;
  }

  /** 按注册顺序返回全部 Skill 名称，供测试与诊断使用。 */
  static List<String> names() {
    return List.copyOf(SKILL_DESCRIPTIONS.keySet());
  }

  /** 返回某条 Skill 的 description；不存在时抛 {@link IllegalArgumentException}。 */
  static String description(String name) {
    String desc = SKILL_DESCRIPTIONS.get(name);
    if (desc == null) {
      throw new IllegalArgumentException("未知 Skill: " + name);
    }
    return desc;
  }

  /**
   * 从 classpath {@code /skills/<name>.md} 读取 UTF-8 正文。
   *
   * <p>资源缺失、读取 IO 异常或 trim 后为空时抛 {@link IllegalStateException}，使打包问题 在启动时暴露而非运行期静默丢失。
   *
   * @param name Skill 名称（kebab-case，与资源文件名一致）
   * @return 非 null、非空正文
   */
  static String load(String name) {
    Objects.requireNonNull(name, "name");
    String resource = "/skills/" + name + ".md";
    try (InputStream in = DemoSkills.class.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("Skill 资源缺失，无法加载: " + resource);
      }
      String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      if (text.isBlank()) {
        throw new IllegalStateException("Skill 正文为空: " + resource);
      }
      return text.strip();
    } catch (IOException e) {
      throw new IllegalStateException("读取 Skill 资源失败: " + resource, e);
    }
  }
}
