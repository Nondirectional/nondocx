package io.github.nondirectional.docx.examples.eval;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * 一条可重复运行的 docx Agent 评测任务。
 *
 * <p>任务由五部分组成：稳定 id、人可读标题、给模型的自然语言 prompt、可重复生成的初始文档 {@link Seed}，以及一组确定性断言 {@link Check}。断言直接读取最终
 * docx，不依赖模型自述，也不使用 LLM-as-judge。
 *
 * <p><b>为什么 seed 用代码而不是二进制 fixture。</b> 每条任务都从零构造文档，评审时能直接看到输入 结构；不依赖 Word/WPS 生成的文件，也不会因为二进制
 * fixture 损坏而静默漂移。
 */
public final class EvalTask {

  /** 初始文档构造器：在空 {@link Document} 上写入任务所需的种子内容。 */
  @FunctionalInterface
  public interface Seed {
    void apply(Document doc);
  }

  /** 一条确定性断言：失败时抛 {@link AssertionError}，消息必须包含期望/实际。 */
  @FunctionalInterface
  public interface Check {
    void verify(Document doc) throws Exception;
  }

  /** 带名称的断言，报告中按名称展示通过/失败。 */
  public static final class NamedCheck {

    private final String name;
    private final Check check;

    private NamedCheck(String name, Check check) {
      this.name = Objects.requireNonNull(name, "name");
      this.check = Objects.requireNonNull(check, "check");
    }

    public static NamedCheck of(String name, Check check) {
      return new NamedCheck(name, check);
    }

    public String name() {
      return name;
    }

    public Check check() {
      return check;
    }
  }

  private final String id;
  private final String title;
  private final String prompt;
  private final Seed seed;
  private final List<NamedCheck> checks;
  private final boolean capabilityGap;

  /**
   * 构造一条普通任务。
   *
   * @param id 稳定 id（kebab-case，报告与 --tasks 过滤使用）
   * @param title 人可读标题
   * @param prompt 给模型的自然语言请求
   * @param seed 初始文档构造器
   * @param checks 确定性断言列表；至少一条
   */
  public EvalTask(String id, String title, String prompt, Seed seed, List<NamedCheck> checks) {
    this(id, title, prompt, seed, checks, false);
  }

  /**
   * 构造一条任务。
   *
   * @param capabilityGap 该任务是否用于暴露已知工具面能力缺口（仅影响报告分类，不影响判定）
   */
  public EvalTask(
      String id,
      String title,
      String prompt,
      Seed seed,
      List<NamedCheck> checks,
      boolean capabilityGap) {
    this.id = requireNonBlank(id, "id");
    this.title = requireNonBlank(title, "title");
    this.prompt = requireNonBlank(prompt, "prompt");
    this.seed = Objects.requireNonNull(seed, "seed");
    this.checks = List.copyOf(Objects.requireNonNull(checks, "checks"));
    this.capabilityGap = capabilityGap;
    if (this.checks.isEmpty()) {
      throw new IllegalArgumentException("任务至少需要一条 check: " + id);
    }
  }

  public String id() {
    return id;
  }

  public String title() {
    return title;
  }

  public String prompt() {
    return prompt;
  }

  public List<NamedCheck> checks() {
    return checks;
  }

  /** 该任务是否用于暴露已知工具面能力缺口（仅报告分类用）。 */
  public boolean capabilityGap() {
    return capabilityGap;
  }

  /**
   * 生成初始文档到指定路径。
   *
   * <p>每次调用都新建文档；同一任务多次运行之间互不共享 {@link Document} 状态。
   *
   * @param path 输出 .docx 路径
   */
  public void writeSeed(Path path) {
    try (Document doc = Docx.create()) {
      seed.apply(doc);
      doc.save(path);
    }
  }

  private static String requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("任务字段不能为空: " + field);
    }
    return value;
  }
}
