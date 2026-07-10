package com.non.docx.toolkit.orchestration.commit;

import com.non.docx.toolkit.orchestration.Operation;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * MergedPlan 的固定优先级排序规则。
 *
 * <p>第一版默认顺序（父任务决策）：
 *
 * <ol>
 *   <li>结构变更（增删段落/表格/行列）
 *   <li>文本/样式变更（替换文本、改样式）
 *   <li>修订相关操作（accept/reject/insert/delete revision）
 *   <li>质量复查
 *   <li>保存前检查
 * </ol>
 *
 * <p><b>OOXML 三层递进（提交顺序）：</b>
 *
 * <ul>
 *   <li><b>OOXML</b>：.docx 的结构变更与内容变更在 XML 里混在一起，没有「先改结构再改内容」的天然约束。
 *   <li><b>POI</b>：活对象上的写入顺序即生效顺序——如果先改文本再删段落，删除可能让前面的文本引用失效。
 *   <li><b>nondocx</b>：在编排层强制固定优先级——先做结构变更（让索引稳定），再做文本/样式变更（在新结构
 *       上改内容），再做修订相关操作（依赖稳定结构），最后质量复查与保存前检查。这避免了「先改文本后改 结构导致索引漂移」这类隐蔽 bug。
 * </ul>
 *
 * <p>提交/保存/关闭属于 coordinator 生命周期动作，不进入专家 plan 排序，故本类不涉及。
 *
 * <p>稳定性：排序是<b>稳定</b>的——同优先级 operation 保持原相对顺序。
 */
public final class CommitPriority {

  /** 提交优先级档位，值越小越先执行。 */
  public enum Tier {
    STRUCTURE(0),
    TEXT_STYLE(1),
    REVISION(2),
    QUALITY_RECHECK(3),
    PRE_SAVE_CHECK(4);

    private final int order;

    Tier(int order) {
      this.order = order;
    }

    public int order() {
      return order;
    }
  }

  /**
   * 把 operation 的 {@code (toolGroup, kind)} 映射到优先级档位。
   *
   * <p>第一版用启发式规则：
   *
   * <ul>
   *   <li>toolGroup={@code revision} -> REVISION
   *   <li>kind 以 {@code insert_}/{@code delete_}/{@code add_}/{@code remove_} 开头且非 revision ->
   *       STRUCTURE（增删类视为结构变更）
   *   <li>kind 以 {@code check_}/{@code verify_} 开头 -> QUALITY_RECHECK
   *   <li>kind 以 {@code pre_save} 开头 -> PRE_SAVE_CHECK
   *   <li>其余（替换文本、改样式等）-> TEXT_STYLE
   * </ul>
   *
   * <p>此映射可被 runtime 的 CommitCoordinator 覆写以适配具体工具组。
   */
  public Tier tierOf(Operation op) {
    String group = op.toolGroup();
    String kind = op.kind();
    if ("revision".equals(group)) {
      return Tier.REVISION;
    }
    if (kind.startsWith("check_") || kind.startsWith("verify_")) {
      return Tier.QUALITY_RECHECK;
    }
    if (kind.startsWith("pre_save")) {
      return Tier.PRE_SAVE_CHECK;
    }
    if (kind.startsWith("insert_")
        || kind.startsWith("delete_")
        || kind.startsWith("add_")
        || kind.startsWith("remove_")) {
      return Tier.STRUCTURE;
    }
    return Tier.TEXT_STYLE;
  }

  /** 返回按固定优先级排序后的 operation 列表（稳定排序，不修改原列表）。 */
  public List<Operation> sortByPriority(List<Operation> operations) {
    Map<Tier, Integer> order = new EnumMap<>(Tier.class);
    for (Tier t : Tier.values()) {
      order.put(t, t.order());
    }
    return operations.stream()
        .sorted(Comparator.comparingInt(op -> order.get(tierOf(op))))
        .collect(Collectors.toList());
  }
}
