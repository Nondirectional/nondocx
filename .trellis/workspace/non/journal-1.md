# Journal - non (Part 1)

> AI development session journal
> Started: 2026-06-16

---


## 2026-06-16 — nondocx-core MVP implemented (task `06-16-nondocx-core-mvp`)

Implemented the full nondocx-core MVP (docx read/write library over Apache POI) across Phases 0–8:

- **Scaffold**: parent POM `com.non:nondocx-parent` + `nondocx-core`, Apache 2.0, README, CI (JDK
  [11,17,21] matrix), Spotless (google-java-format) bound to `verify`.
- **Domain model**: `Docx` facade; `api/` (Document, text/Paragraph/Run/Hyperlink,
  table/Table/Row/Cell, section/Section/PaperSize/Orientation, image/Image/ImageType,
  header/Header/Footer, style/*, exception/DocxException hierarchy, BodyElement/InlineElement);
  `builder/` (DocumentBuilder/ParagraphBuilder/TableBuilder); `internal/` (poi/Mappers, Numbering,
  Pictures; util/Streams, Objects).
- **111 tests**, content-equal equals on all core types; `RoundTripTest` deep-equality across
  save→open green on first try (POI write-side normalization is invisible to equality because
  equals compares parsed values, not raw XML).
- All 9 prd acceptance criteria PASS; `trellis-check` verdict READY-TO-FINISH.
- Key Apache POI bridge gotchas (pre-populated children, EMU vs pixels, `unsetNumPr` vs
  `setNumID(null)`, create-on-access header/footer, image-in-run) captured in
  `spec/backend/poi-bridge.md` → Implementation Notes.
- **Deferred to v0.2**: OOXML template fixtures / TestDocxPackager (core acceptance met via
  programmatic construction + POI cross-reference); first-page/even-page header variants.


