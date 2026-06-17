package com.non.docx.core.builder;

import com.non.docx.core.api.table.Row;
import com.non.docx.core.api.table.Table;
import com.non.docx.core.internal.util.Objects;
import java.util.function.Consumer;

/**
 * 构建轨道辅助类，用于组装单个 {@link Table}。
 *
 * <p>这是一个对活动对象 {@link Table} 的薄包装器。行的创建委托给活动表格的构建块 （{@link Table#addRow()} 和 lambda 便捷方法 {@link
 * Table#row(Consumer)}）； 此处不重复任何行或单元格行为——每次调用都到达活动对象 {@code Table} / {@code Row} / {@code Cell}。
 *
 * <p>示例：
 *
 * <pre>{@code
 * TableBuilder.on(table)
 *     .row(r -> r.cell("A1").cell("B1"))
 *     .row(r -> r.cell("A2").cell("B2"));
 * }</pre>
 *
 * 如需从零组装表格，推荐使用 {@link DocumentBuilder#table(Consumer)}，它将活动表格直接交给 lambda；此类适用于希望使用显式构建器对象而非 lambda
 * 的调用方。
 *
 * <p>此类仅引用 {@code api/} 类型——其签名中不出现 POI 类型。
 */
public final class TableBuilder {

  private final Table table;

  private TableBuilder(Table table) {
    this.table = table;
  }

  /**
   * 在给定的活动表格上创建一个构建器。
   *
   * @param table 要组装成的活动表格（不能为 {@code null}）
   * @return 新构建器
   * @throws IllegalArgumentException 如果 {@code table} 为 {@code null}
   */
  public static TableBuilder on(Table table) {
    Objects.requireNonNull(table, "table");
    return new TableBuilder(table);
  }

  /**
   * 追加一个新的空行并返回活动行，以便调用方可以直接填充其单元格 （例如 {@code .row().cell("A1").cell("B1")}）。
   *
   * @return 新追加的活动行
   */
  public Row row() {
    return table.addRow();
  }

  /**
   * 追加一个新行，对其应用给定配置器，并返回此构建器。配置器操作活动对象 {@link Row}。
   *
   * <p>这委托给 {@link Table#row(Consumer)}；不重复任何行或单元格逻辑。
   *
   * @param config 行配置器，操作活动行（不能为 {@code null}）
   * @return 此构建器
   * @throws IllegalArgumentException 如果 {@code config} 为 {@code null}
   */
  public TableBuilder row(Consumer<Row> config) {
    Objects.requireNonNull(config, "config");
    table.row(config);
    return this;
  }

  /**
   * 返回此构建器组装的活动表格。
   *
   * @return 底层的活动表格（从不 {@code null}）
   */
  public Table table() {
    return table;
  }
}
