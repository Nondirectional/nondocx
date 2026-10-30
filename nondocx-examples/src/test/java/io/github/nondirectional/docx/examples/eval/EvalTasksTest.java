package io.github.nondirectional.docx.examples.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import io.github.nondirectional.docx.core.Docx;
import io.github.nondirectional.docx.core.api.Document;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 验证评测集本身的质量：任务数量、seed 可生成，以及最重要的——check 在未修改 seed 上必须失败。
 *
 * <p>最后一条是防止 tautological test：如果种子文档不满足任务目标，而 check 仍然通过， 说明这条 check 根本没测到模型的改动。
 */
class EvalTasksTest {

  @TempDir Path temp;

  @Test
  void hasTwentyFiveUniqueTasks() {
    List<EvalTask> tasks = EvalTasks.all();
    assertThat(tasks).hasSize(25);

    Set<String> ids = new HashSet<>();
    for (EvalTask task : tasks) {
      assertThat(task.id()).isNotBlank();
      assertThat(ids.add(task.id())).as("任务 id 重复: %s", task.id()).isTrue();
      assertThat(task.title()).isNotBlank();
      assertThat(task.prompt()).isNotBlank();
      assertThat(task.checks()).as("%s 的 check 不应为空", task.id()).isNotEmpty();
    }
  }

  @Test
  void hasFiveCreateTasksWithUniqueIdsAcrossGroups() {
    List<EvalTask> create = EvalTasks.create();
    assertThat(create).hasSize(5);

    Set<String> ids = new HashSet<>(EvalTasks.all().size() + create.size());
    for (EvalTask task : create) {
      assertThat(task.id()).startsWith("create-");
      // 两组之间 id 也不得重复（byId/catalog 覆盖两组）
      assertThat(ids.add(task.id())).as("跨组任务 id 重复: %s", task.id()).isTrue();
      assertThat(task.checks()).as("%s 的 check 不应为空", task.id()).isNotEmpty();
    }
    for (EvalTask task : EvalTasks.all()) {
      assertThat(ids.add(task.id())).as("跨组任务 id 重复: %s", task.id()).isTrue();
    }
  }

  @Test
  void groupResolvesByName() {
    assertThat(EvalTasks.group("baseline")).isEqualTo(EvalTasks.all());
    assertThat(EvalTasks.group("create")).isEqualTo(EvalTasks.create());
    assertThat(EvalTasks.group(null)).isEqualTo(EvalTasks.all());
    assertThat(EvalTasks.group("nope")).isNull();
  }

  @Test
  void catalogMatchesTasks() {
    assertThat(EvalTasks.catalog()).hasSize(EvalTasks.all().size() + EvalTasks.create().size());
    for (EvalTask task : EvalTasks.all()) {
      assertThat(EvalTasks.catalog()).containsEntry(task.id(), task.title());
      assertThat(EvalTasks.byId(task.id())).isSameAs(task);
    }
    for (EvalTask task : EvalTasks.create()) {
      assertThat(EvalTasks.catalog()).containsEntry(task.id(), task.title());
      assertThat(EvalTasks.byId(task.id())).isSameAs(task);
    }
    assertThat(EvalTasks.byId("does-not-exist")).isNull();
  }

  @Test
  void marksKnownCapabilityGapTasks() {
    // full 面静态能力缺口任务（compiler 面全部可过；flag 仅影响 full 面报告的分类列）。
    // 2026-10-30 full 面复跑（runId 20261030-125616-728）逐条 trace 确认后加入。
    assertThat(
            EvalTasks.all().stream()
                .filter(EvalTask::capabilityGap)
                .map(EvalTask::id)
                .collect(java.util.stream.Collectors.toList()))
        .containsExactly(
            "footer-page-number",
            "comment-add",
            "fill-template",
            "insert-list",
            "delete-paragraph",
            "table-add-row",
            "multi-step-combo",
            "fill-template-conditional-false",
            "bold-existing-phrase",
            "header-write-text");
  }

  @Test
  void everySeedCanBeWrittenAndReopened() {
    for (EvalTask task : allTasks()) {
      Path path = temp.resolve(task.id() + ".docx");
      task.writeSeed(path);
      assertThat(path).as("%s seed 未生成", task.id()).exists();
      try (Document doc = Docx.open(path)) {
        assertThat(doc.paragraphs()).as("%s seed 读回为空", task.id()).isNotEmpty();
      }
    }
  }

  @Test
  void everyTaskFailsAtLeastOneCheckOnUnmodifiedSeed() {
    for (EvalTask task : allTasks()) {
      Path path = temp.resolve(task.id() + "-seed.docx");
      task.writeSeed(path);
      try (Document doc = Docx.open(path)) {
        boolean anyFailed = false;
        for (EvalTask.NamedCheck check : task.checks()) {
          try {
            check.check().verify(doc);
          } catch (AssertionError expected) {
            anyFailed = true;
          } catch (Exception unexpected) {
            fail(
                "任务 %s 的 check「%s」在 seed 上抛出非 AssertionError: %s",
                task.id(), check.name(), unexpected);
          }
        }
        assertThat(anyFailed)
            .as("任务 %s 的所有 check 在未修改 seed 上都能通过，check 可能是恒真的", task.id())
            .isTrue();
      }
    }
  }

  /** baseline + create 两组任务（防恒真与 seed 校验覆盖两组）。 */
  private static List<EvalTask> allTasks() {
    List<EvalTask> all = new java.util.ArrayList<>(EvalTasks.all());
    all.addAll(EvalTasks.create());
    return all;
  }
}
