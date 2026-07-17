package com.non.docx.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.non.chain.Message;
import com.non.chain.tool.Tool;
import com.non.chain.tool.ToolRegistry;
import com.non.docx.toolkit.DocxToolkit;
import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证只读复审 SubAgent {@code review_intent} 的核心不变量：其 ToolRegistry <b>仅含 {@code view_*} 只读工具</b>，
 * 不含任何写工具、保存工具或嵌套 SubAgent。
 *
 * <p>这是 {@code agent-single.md} spec 允许 SubAgent 例外的<b>物理保证</b>：只读复审 SubAgent 无法改文档、无法保存， 因此不触发历史
 * SubAgent 的「自述≠真相」鸿沟。本测试锁定该保证，防止未来误给复审 SubAgent 注入写工具。
 *
 * <p>复审工具集的构造逻辑（{@code new ToolRegistry().scan(toolkit.view)}）与 {@link
 * AgentBridge#buildToolRegistry()} 中 {@code review_intent} 的 {@code toolRegistry(...)}
 * 完全一致——故此处复现即等价于断言生产代码的复审 SubAgent 工具集。
 */
class ReviewSubAgentTest {

  /** 复审 SubAgent 的工具集（与 AgentBridge.buildToolRegistry 中 review_intent 的 toolRegistry 同构）。 */
  private static ToolRegistry reviewToolRegistry() {
    return new ToolRegistry().scan(new DocxToolkit().view);
  }

  @Test
  void reviewSubAgentToolsAreAllReadonly() {
    List<Tool> tools = reviewToolRegistry().getRegularTools();
    assertFalse(tools.isEmpty(), "复审 SubAgent 应至少含 view_* 工具");
    for (Tool t : tools) {
      assertTrue(
          AgentBridge.isReadonly(t.name()), "复审 SubAgent 工具 " + t.name() + " 必须只读（不置 dirty）");
    }
  }

  @Test
  void reviewSubAgentHasNoWriteOrSaveTools() {
    ToolRegistry registry = reviewToolRegistry();
    List<String> forbidden =
        List.of(
            "insert_paragraph",
            "replace_run_text",
            "create_table",
            "set_cell_text",
            "save_current_document",
            "save_docx",
            "apply_tracked_changes");
    for (String name : forbidden) {
      assertFalse(registry.hasTool(name), "复审 SubAgent 不得含写/保存工具: " + name);
    }
  }

  @Test
  void reviewSubAgentHasNoNestedSubAgent() {
    // 仅一层委派：复审 SubAgent 自身不得再注册 SubAgent，否则 build() fail-fast。
    assertEquals(List.of(), reviewToolRegistry().subAgentNames(), "复审 SubAgent 不得嵌套 SubAgent");
  }

  /** review_intent 工具名（主 Agent DIRECT 模式下暴露的工具名）必须被识别为只读，不置 dirty。 */
  @Test
  void reviewIntentToolNameIsReadonly() {
    assertTrue(AgentBridge.isReadonly(AgentBridge.REVIEW_TOOL));
    assertEquals("review_intent", AgentBridge.REVIEW_TOOL);
  }

  /**
   * 复审 SubAgent 的 {@code view_*} 工具需要 {@code doc_id} 才能读文档。复审上下文必须注入当前 doc_id，否则复审 SubAgent
   * 读不到文档、把正常完成的修改误判为「未达成」（实测回归点）。
   */
  @Test
  void reviewContextInjectsDocIdAndRequest() throws Exception {
    AgentBridge bridge = new AgentBridge(null, "ignored.docx"); // disabled，无 API key
    setField(bridge, "docId", "doc-42");
    setField(bridge, "currentTurnRequest", "在文档开头添加居中标题");

    @SuppressWarnings("unchecked")
    List<Message> ctx =
        (List<Message>)
            invokeMethod(
                bridge,
                "selectReviewContext",
                new Class[] {List.class, Message.class, String.class},
                List.of(),
                null,
                "{}");

    assertEquals(1, ctx.size(), "复审上下文应只含一条注入消息");
    String prompt = ctx.get(0).content();
    assertTrue(prompt.contains("doc-42"), "复审上下文必须注入 doc_id: " + prompt);
    assertTrue(prompt.contains("添加居中标题"), "复审上下文必须含本轮用户请求: " + prompt);
  }

  private static void setField(Object target, String name, Object value) throws Exception {
    Field f = target.getClass().getDeclaredField(name);
    f.setAccessible(true);
    f.set(target, value);
  }

  private static Object invokeMethod(Object target, String name, Class<?>[] params, Object... args)
      throws Exception {
    java.lang.reflect.Method m = target.getClass().getDeclaredMethod(name, params);
    m.setAccessible(true);
    return m.invoke(target, args);
  }
}
