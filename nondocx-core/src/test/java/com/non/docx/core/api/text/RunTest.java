package com.non.docx.core.api.text;

import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.RunStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies chainable mutators, round-trip persistence of inline styles, content equality, and the
 * {@code style()} snapshot for {@link Run}.
 */
class RunTest {

    @Test
    void chainableMutatorsApplyLiveThroughTheDelegate() {
        Document doc = Docx.create();
        Run run = doc.addParagraph().addRun();

        Run returned = run.text("hello").bold().italic().underline().fontSize(14).font("Arial").color("FF0000");

        assertThat(returned).isSameAs(run);
        assertThat(run.text()).isEqualTo("hello");
        assertThat(run.isBold()).isTrue();
        assertThat(run.isItalic()).isTrue();
        assertThat(run.isUnderline()).isTrue();
        assertThat(run.fontSize()).isEqualTo(14);
        assertThat(run.font()).isEqualTo("Arial");
        assertThat(run.color()).isEqualTo("FF0000");
    }

    @Test
    void boldItalicUnderlineCanBeClearedViaBooleanOverload() {
        Run run = Docx.create().addParagraph().addRun("x").bold().italic().underline();

        run.bold(false).italic(false).underline(false);

        assertThat(run.isBold()).isFalse();
        assertThat(run.isItalic()).isFalse();
        assertThat(run.isUnderline()).isFalse();
    }

    @Test
    void unsetStyleAttributesAreNull(@TempDir Path tmp) throws Exception {
        Document doc = Docx.create();
        Run run = doc.addParagraph().addRun("plain");

        assertThat(run.isBold()).isFalse();
        assertThat(run.isItalic()).isFalse();
        assertThat(run.isUnderline()).isFalse();
        assertThat(run.fontSize()).isNull();
        assertThat(run.font()).isNull();
        assertThat(run.color()).isNull();

        RunStyle snapshot = run.style();
        assertThat(snapshot).isEqualTo(RunStyle.empty());
    }

    @Test
    void stylesSurviveRoundTrip(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("run.docx");

        Document original = Docx.create();
        original.addParagraph().addRun("styled").bold().italic().underline()
                .fontSize(18).font("Courier New").color("00AA00");
        original.save(file);

        try (Document opened = Docx.open(file)) {
            Run back = opened.paragraph(0).run(0);
            assertThat(back.text()).isEqualTo("styled");
            assertThat(back.isBold()).isTrue();
            assertThat(back.isItalic()).isTrue();
            assertThat(back.isUnderline()).isTrue();
            assertThat(back.fontSize()).isEqualTo(18);
            assertThat(back.font()).isEqualTo("Courier New");
            assertThat(back.color()).isEqualToIgnoringCase("00AA00");
        }
    }

    @Test
    void equalsByContentIgnoresDelegateIdentity() {
        Document a = Docx.create();
        Document b = Docx.create();

        Run r1 = a.addParagraph().addRun("hi").bold().fontSize(12);
        Run r2 = b.addParagraph().addRun("hi").bold().fontSize(12);
        Run r3 = b.addParagraph().addRun("hi");                 // same text, different style
        Run r4 = b.addParagraph().addRun("ho").bold().fontSize(12); // same style, different text

        assertThat(r1).isEqualTo(r2);
        assertThat(r1.hashCode()).isEqualTo(r2.hashCode());
        assertThat(r1).isNotEqualTo(r3);
        assertThat(r1).isNotEqualTo(r4);
        assertThat(r1).isNotEqualTo(null);
        assertThat(r1).isNotEqualTo("not a run");
    }

    @Test
    void textRejectsNull() {
        Run run = Docx.create().addParagraph().addRun();
        assertThatThrownBy(() -> run.text(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");
    }

    @Test
    void rawReturnsSameDelegateInstance() {
        Run run = Docx.create().addParagraph().addRun("x");
        assertThat(run.raw()).isSameAs(run.raw());
    }
}
