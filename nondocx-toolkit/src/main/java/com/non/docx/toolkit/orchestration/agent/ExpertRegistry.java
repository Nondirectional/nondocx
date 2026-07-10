package com.non.docx.toolkit.orchestration.agent;

import com.non.docx.toolkit.orchestration.DocumentSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ExpertAgent} 注册表：RouterAgent 据此分阶段派发。
 *
 * <p>每个工具组子任务落地时把自己的专家注册进来。RouterAgent 调用 {@link #selectRelevant} 做粗分流。
 */
public final class ExpertRegistry {

  private final List<ExpertAgent> agents = new ArrayList<>();

  /** 注册一个专家（可链式）。 */
  public ExpertRegistry register(ExpertAgent agent) {
    agents.add(agent);
    return this;
  }

  /** 全部已注册专家（不可变拷贝）。 */
  public List<ExpertAgent> all() {
    return List.copyOf(agents);
  }

  /** 选出对当前意图 + 快照相关的专家（粗分流）。 */
  public List<ExpertAgent> selectRelevant(String intent, DocumentSnapshot snapshot) {
    List<ExpertAgent> out = new ArrayList<>();
    for (ExpertAgent a : agents) {
      if (a.relevantTo(intent, snapshot)) {
        out.add(a);
      }
    }
    return out;
  }

  /** 已注册专家数量。 */
  public int size() {
    return agents.size();
  }
}
