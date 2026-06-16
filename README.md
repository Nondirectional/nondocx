# nondocx

A fluent, domain-friendly **docx read/write library for Java**, built on top of
[Apache POI](https://poi.apache.org/).

nondocx wraps POI's verbose `XWPF*` API into an intuitive domain model
(`Document`, `Paragraph`, `Run`, `Table`, …) so you can read, build, and modify
`.docx` files with a few lines of code — while keeping a `raw()` escape hatch to
the underlying POI objects for advanced cases.

> **Status:** work in progress (MVP). APIs are not yet stable.

## Features

- Fluent, chainable, live domain objects (`paragraph.text("Hi").bold()`)
- Full read **and** write round-trip for documents, paragraphs, runs, tables,
  images, hyperlinks, lists, sections, headers, and footers
- Self-contained, all-unchecked `DocxException` hierarchy (no POI exceptions leak)
- `raw()` escape hatch on every core type for out-of-scope features
- Targets **JDK 11+** (verified on 11 / 17 / 21 via CI)
- Apache License 2.0

## Quick start

```java
import com.non.docx.core.Docx;
import com.non.docx.core.api.Document;

// Open an existing document
try (Document doc = Docx.open(path)) {
    doc.paragraph(0).run(0).text("Hello, nondocx!").bold();
    doc.save(outPath);
}
```

> A builder track (`DocumentBuilder`) and full examples will be documented as the
> library stabilizes.

## Maven coordinates

```xml
<dependency>
    <groupId>com.non</groupId>
    <artifactId>nondocx-core</artifactId>
    <version>0.0.1-SNAPSHOT</version>
</dependency>
```

Apache POI is pulled in transitively (`compile` scope); no extra configuration is
required on the consumer side.

## Requirements

- Java 11 or above
- A Maven-compatible build (the project is published as a Maven artifact)

## License

Licensed under the [Apache License, Version 2.0](./LICENSE).
