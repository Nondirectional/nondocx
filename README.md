# nondocx

A fluent, domain-friendly **docx read/write library for Java**, built on top of
[Apache POI](https://poi.apache.org/).

nondocx wraps POI's verbose `XWPF*` API into an intuitive domain model
(`Document`, `Paragraph`, `Run`, `Table`, `Section`, …) so you can read, build, and modify
`.docx` files with a few lines of code — while keeping a `raw()` escape hatch to the
underlying POI objects for advanced cases.

> **Status:** work in progress (MVP). APIs are not yet stable and may change before `1.0.0`.

## Features

- **Fluent, chainable, live domain objects** — mutate the document in place: `run.text("Hi").bold()`
- **Full read *and* write round-trip** for documents, paragraphs, runs, tables, images,
  hyperlinks, lists, sections, headers, and footers, verified by deep content-equality tests
- **Mutable live objects + a builder track** — edit existing documents, or assemble new ones
  with `DocumentBuilder`
- **Self-contained, all-unchecked `DocxException` hierarchy** — no `org.apache.poi.*` exceptions
  ever leak into your `catch` clauses
- **`raw()` escape hatch on every core type** — drop down to the underlying `XWPF*` object for
  features outside the deep-wrap scope (tracked changes, fields, OLE, math, …)
- **Zero POI leakage on the public API** — POI types appear *only* in `raw()` return types
- **Targets JDK 11+**, verified on 11 / 17 / 21 via CI
- **Apache License 2.0**

## Quick start

### Open, modify, and save

```java
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import java.nio.file.Path;

// Open an existing document and edit it live.
try (Document doc = Docx.open(Path.of("input.docx"))) {
    // Edit existing content: fluent mutators write straight through to POI.
    doc.paragraph(0).run(0).text("Hello, nondocx!").bold();

    // Append a new styled paragraph.
    doc.addParagraph().addRun("New paragraph").italic().color("FF0000");

    doc.save(Path.of("output.docx"));
}
```

### Build from scratch

```java
import com.non.docx.core.api.Document;
import com.non.docx.core.api.style.HeadingLevel;
import com.non.docx.core.builder.DocumentBuilder;
import java.nio.file.Path;

// Assemble a document declaratively. The configurator lambdas receive the live
// Paragraph / Table, so the full run / cell / style API is available inline.
Document doc = DocumentBuilder.start()
    .heading(HeadingLevel.H1, "Quarterly Report")
    .paragraph(p -> p.addRun("Summary").bold().fontSize(14))
    .table(t -> t
        .row(r -> r.cell("Metric").cell("Value"))
        .row(r -> r.cell("Revenue").cell("$1.2M")))
    .build();

doc.save(Path.of("report.docx"));
```

The `Document` returned by `build()` is the same kind of live, mutable document you get from
`Docx.open` / `Docx.create` — keep mutating it, or close it when done.

### Using the escape hatch

For docx features that are outside the deep-wrap scope, reach for `raw()`:

```java
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

try (Document doc = Docx.open(Path.of("in.docx"))) {
    XWPFDocument raw = doc.raw(); // the backing POI object; modify with caution
    // ... use any POI capability not wrapped by nondocx ...
}
```

> POI exceptions thrown through the `raw()` path propagate as-is — `raw()` is POI's territory.

## Maven coordinates

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>nondocx-core</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Apache POI is pulled in transitively (`compile` scope); no extra configuration is required on
the consumer side.

## Requirements

- **Java 11 or above**
- A Maven-compatible build (the project is published as a Maven artifact)

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).
