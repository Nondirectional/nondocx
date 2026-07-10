package com.non.docx.toolkit.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Router 状态机单测：覆盖合法推进、REVIEW 条件跳过、终态与非法推进。 */
class RouterStateTest {

  @Test
  void analyzeToPlanToCommitToDone() {
    // REVIEW 条件触发：PLAN 可直接进 COMMIT，跳过 REVIEW
    assertThat(RouterState.canTransition(RouterState.ANALYZE, RouterState.PLAN)).isTrue();
    assertThat(RouterState.canTransition(RouterState.PLAN, RouterState.COMMIT)).isTrue();
    assertThat(RouterState.canTransition(RouterState.COMMIT, RouterState.DONE)).isTrue();
  }

  @Test
  void planCanEnterReview() {
    // REVIEW 条件触发：PLAN 也可先进 REVIEW
    assertThat(RouterState.canTransition(RouterState.PLAN, RouterState.REVIEW)).isTrue();
    assertThat(RouterState.canTransition(RouterState.REVIEW, RouterState.COMMIT)).isTrue();
    assertThat(RouterState.canTransition(RouterState.REVIEW, RouterState.FAILED)).isTrue();
  }

  @Test
  void commitCanFail() {
    assertThat(RouterState.canTransition(RouterState.COMMIT, RouterState.FAILED)).isTrue();
  }

  @Test
  void terminalsHaveNoSuccessors() {
    assertThat(RouterState.DONE.successors()).isEmpty();
    assertThat(RouterState.FAILED.successors()).isEmpty();
    assertThat(RouterState.DONE.isTerminal()).isTrue();
    assertThat(RouterState.FAILED.isTerminal()).isTrue();
  }

  @Test
  void illegalTransitionThrows() {
    // ANALYZE 不能直接到 COMMIT
    assertThat(RouterState.canTransition(RouterState.ANALYZE, RouterState.COMMIT)).isFalse();
    assertThatThrownBy(() -> RouterState.transition(RouterState.ANALYZE, RouterState.COMMIT))
        .isInstanceOf(IllegalStateException.class);
    // DONE 不能再推进
    assertThatThrownBy(() -> RouterState.transition(RouterState.DONE, RouterState.ANALYZE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void transitionReturnsNewState() {
    assertThat(RouterState.transition(RouterState.ANALYZE, RouterState.PLAN))
        .isEqualTo(RouterState.PLAN);
  }
}
