package com.non.docx.toolkit.orchestration.specialist;

import com.non.docx.toolkit.TrackedChangeQueryTools;
import com.non.docx.toolkit.orchestration.ConflictKey;
import com.non.docx.toolkit.orchestration.Operation;
import com.non.docx.toolkit.orchestration.commit.OperationExecutionException;
import com.non.docx.toolkit.orchestration.commit.OperationExecutor;
import com.non.docx.toolkit.orchestration.session.OrchestratorSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 修订域的 {@link OperationExecutor}：把 revision 域的 {@link Operation} 落到 {@link
 * TrackedChangeQueryTools}。
 *
 * <p><b>OOXML 三层递进（修订 operation 映射）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：修订是 {@code <w:ins>}（插入）、{@code <w:del>}（删除）等带作者/时间戳的元素； accept/reject 在 XML
 *       上体现为「保留改动并去标记」或「回退改动并去标记」。
 *   <li><b>POI</b>：{@code XWPFDocument} 的修订处理经 {@code TrackedChanges} 封装为 accept(id)/reject(id)。
 *   <li><b>nondocx</b>：{@code TrackedChangeQueryTools.applyTrackedChanges} 提供批量 accept/reject；
 *       本执行器把单条 operation 包装成单次调用。
 * </ul>
 *
 * <p><b>支持的 operation kind：</b>
 *
 * <ul>
 *   <li>{@code apply_revision}——处理指定 id 的修订。payload: {@code action}(ACCEPT/REJECT), {@code
 *       target}(TEXT_OR_MOVE/PROPERTY/CELL), {@code ids}(stable id 列表)。
 * </ul>
 */
public final class RevisionExecutor implements OperationExecutor {

  private final TrackedChangeQueryTools trackedChangeQuery;

  public RevisionExecutor(TrackedChangeQueryTools trackedChangeQuery) {
    this.trackedChangeQuery = trackedChangeQuery;
  }

  @Override
  public boolean canHandle(Operation operation) {
    return "revision".equals(operation.toolGroup());
  }

  @Override
  public String execute(OrchestratorSession session, Operation operation)
      throws OperationExecutionException {
    String docId = session.docId();
    Map<String, Object> payload = operation.payload();
    String kind = operation.kind();
    if (!"apply_revision".equals(kind)) {
      throw new OperationExecutionException("revision 域不支持的 operation kind: " + kind);
    }
    String action = strPayload(payload, "action");
    String target = strPayload(payload, "target");
    @SuppressWarnings("unchecked")
    List<String> ids = (List<String>) payload.getOrDefault("ids", List.of());
    if (ids instanceof List) {
      // ok
    } else {
      ids = List.of(String.valueOf(ids));
    }
    try {
      String result =
          trackedChangeQuery.applyTrackedChanges(docId, action, target, new ArrayList<>(ids));
      if (com.non.docx.toolkit.orchestration.commit.ToolResultChecks.isFailure(result)) {
        throw new OperationExecutionException(result);
      }
      return result;
    } catch (OperationExecutionException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new OperationExecutionException("revision/" + kind + " 执行异常", e);
    }
  }

  private static String strPayload(Map<String, Object> payload, String key) {
    Object v = payload.get(key);
    if (v == null) {
      throw new OperationExecutionException("revision operation 缺少字段 " + key);
    }
    return String.valueOf(v);
  }

  /** 构造一条 apply_revision operation。 */
  public static Operation applyRevision(
      String opId, String action, String target, List<String> ids, String intent) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("action", action);
    payload.put("target", target);
    payload.put("ids", ids);
    String targetRef = "rev:" + String.join(",", ids);
    return Operation.of(
        opId,
        "revision",
        "apply_revision",
        targetRef,
        payload,
        new ConflictKey("revision", "apply_revision", targetRef),
        intent,
        action + " 修订 " + target + " " + ids,
        "");
  }
}
