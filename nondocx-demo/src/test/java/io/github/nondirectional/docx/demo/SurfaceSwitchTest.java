package io.github.nondirectional.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.chain.provider.VLLM;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolRegistry;
import io.github.nondirectional.docx.toolkit.DocxToolkit;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 工具面切换（2026-10-30）的核心契约：demo 默认 <b>compiler 面</b>（8 入口），{@code NONDOCX_AGENT_SURFACE=full}
 * 回退原细粒度面。
 *
 * <p>锁定三件事：
 *
 * <ol>
 *   <li><b>compiler 面恰为 8 入口</b>：current_document + create/read/validate/edit_document +
 *       fill_template + render_preview（7 regular）+ review_intent SubAgent——与 eval 的 compiler 面同构
 *       （docs/11 §6.3~§6.7 的 A/B 数据即此装配），不得混入 full 面写工具。
 *   <li><b>full 面不回归</b>：细粒度工具原样保留，回退路径可用。
 *   <li><b>面配套随面切换</b>：system prompt 与 Skill 策略（full 面 Skill 教的是 compiler 面没有的 工具，注入即误导）。
 * </ol>
 */
class SurfaceSwitchTest {

  private static ToolRegistry registry(String surface) {
    DocxToolkit toolkit = new DocxToolkit();
    DocumentTools documentTools =
        new DocumentTools(toolkit, () -> "doc-0", () -> java.nio.file.Path.of("ignored.docx"));
    // VLLM 仅作复审 SubAgent 的 llm 占位：build() 不发网络请求
    VLLM llm = new VLLM("http://127.0.0.1:1", "test-key", "test-model");
    return AgentBridge.buildToolRegistry(
        surface, documentTools, toolkit, llm, (history, newMessage, args) -> List.of());
  }

  private static List<String> regularNames(ToolRegistry registry) {
    return registry.getRegularTools().stream().map(Tool::name).collect(Collectors.toList());
  }

  @Test
  void compilerSurfaceExposesExactlyEightEntries() {
    ToolRegistry registry = registry(AgentBridge.SURFACE_COMPILER);
    assertEquals(
        List.of(
            "create_document",
            "current_document",
            "edit_document",
            "fill_template",
            "read_document",
            "render_preview",
            "validate_document"),
        regularNames(registry).stream().sorted().collect(Collectors.toList()),
        "compiler 面主工具应为 current_document + 6 编译工具");
    assertEquals(List.of(AgentBridge.REVIEW_TOOL), registry.subAgentNames());
  }

  @Test
  void compilerSurfaceHasNoFullSurfaceWriteTools() {
    ToolRegistry registry = registry(AgentBridge.SURFACE_COMPILER);
    for (String forbidden :
        List.of(
            "insert_paragraph",
            "replace_run_text",
            "create_table",
            "update_table_cell_shading",
            "insert_tracked_run")) {
      assertFalse(registry.hasTool(forbidden), "compiler 面不得混入 full 面工具: " + forbidden);
    }
  }

  @Test
  void fullSurfaceKeepsLegacyFineGrainedTools() {
    ToolRegistry registry = registry(AgentBridge.SURFACE_FULL);
    for (String expected :
        List.of(
            "current_document",
            "insert_paragraph",
            "replace_run_text",
            "create_table",
            "replace_table_cell_run_text",
            "insert_tracked_run")) {
      assertTrue(registry.hasTool(expected), "full 面应保留细粒度工具: " + expected);
    }
    assertTrue(registry.getRegularTools().size() >= 50, "full 面应保持 ~58 入口基线");
    assertEquals(List.of(AgentBridge.REVIEW_TOOL), registry.subAgentNames());
  }

  @Test
  void compilerToolsDirtyClassification() {
    // validate_document 只读（质量检查不改文档）——纯咨询轮不应触发落盘
    assertTrue(AgentBridge.isReadonly("validate_document"), "validate_document 应只读");
    assertTrue(AgentBridge.isReadonly("read_document"), "read_document 应只读");
    assertTrue(AgentBridge.isReadonly("current_document"), "current_document 应只读");
    assertTrue(AgentBridge.isReadonly("render_preview"), "render_preview 应只读（只落产物文件，不改文档）");
    // 写工具走安全默认（未知即写）——create/edit/fill 必须置 dirty
    assertFalse(AgentBridge.isReadonly("edit_document"), "edit_document 应视为写");
    assertFalse(AgentBridge.isReadonly("create_document"), "create_document 应视为写");
    assertFalse(AgentBridge.isReadonly("fill_template"), "fill_template 应视为写");
  }

  @Test
  void systemPromptMatchesSurface() {
    String compiler = AgentBridge.systemPromptFor(AgentBridge.SURFACE_COMPILER);
    assertTrue(compiler.contains("create_document"), "compiler prompt 应教 create_document");
    assertTrue(compiler.contains("edit_document"), "compiler prompt 应教 edit_document");
    assertTrue(compiler.contains("tracked.replace"), "compiler prompt 应教修订 op");
    assertTrue(compiler.contains("tracked.set_style"), "compiler prompt 应教修订格式 op");
    assertTrue(compiler.contains("footer.set_page_number"), "compiler prompt 应教页脚页码 op");
    assertTrue(compiler.contains("comment.reply"), "compiler prompt 应教批注回复 op");
    assertTrue(compiler.contains("不得调用或寻找"), "两面 prompt 都必须禁止保存工具");
    assertFalse(compiler.contains("[Skill]"), "compiler prompt 不应引用 Skill（面内无 Skill）");

    String full = AgentBridge.systemPromptFor(AgentBridge.SURFACE_FULL);
    assertTrue(full.contains("写工具"), "full prompt 保持细粒度面语义");
    assertTrue(full.contains("[Skill]"), "full prompt 保留 Skill 使用指引");
  }

  @Test
  void skillPolicyMatchesSurface() {
    assertTrue(
        AgentBridge.skillRegistryFor(AgentBridge.SURFACE_COMPILER).skillNames().isEmpty(),
        "compiler 面不得注入 full 面 Skill（教的是不存在的工具）");
    assertEquals(6, AgentBridge.skillRegistryFor(AgentBridge.SURFACE_FULL).skillNames().size());
  }

  @Test
  void surfaceResolutionDefaultsToCompiler() {
    assertEquals(AgentBridge.SURFACE_COMPILER, AgentBridge.surfaceFromRaw(null));
    assertEquals(AgentBridge.SURFACE_COMPILER, AgentBridge.surfaceFromRaw("  "));
    assertEquals(AgentBridge.SURFACE_COMPILER, AgentBridge.surfaceFromRaw("COMPILER"));
    assertEquals(AgentBridge.SURFACE_FULL, AgentBridge.surfaceFromRaw(" full "));
    assertEquals(AgentBridge.SURFACE_FULL, AgentBridge.surfaceFromRaw("FULL"));
    // 非法值回退默认（demo 保持可用），不抛异常
    assertEquals(AgentBridge.SURFACE_COMPILER, AgentBridge.surfaceFromRaw("fast"));
  }
}
