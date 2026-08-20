# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build executable JAR
mvn clean install spring-boot:repackage

# Build without tests
mvn clean install -DskipTests

# Run all tests
mvn test

# Run a specific test class
mvn test -Dtest=MatchingServiceTest

# Check code formatting (Google Java Format via Spotless)
mvn spotless:check

# Apply code formatting
mvn spotless:apply
```

## Running the service

Always pass `--enable-native-access=ALL-UNNAMED`: the name parser is `name-parser-rust`, which
downcalls a Rust cdylib over FFM (`java.lang.foreign`). Without the flag the JVM warns on every
run, and a future JDK will refuse the call outright.

```bash
# Run with an existing index
java --enable-native-access=ALL-UNNAMED -jar target/matching-ws-*-exec.jar --mode=RUN --index.path=/tmp/index

# Build index from CSV then serve
java --enable-native-access=ALL-UNNAMED -jar target/matching-ws-*-exec.jar --mode=INDEX_CSV --index.path=/tmp/index --export.path=/tmp/export/

# Build index from ChecklistBank DB then serve
java --enable-native-access=ALL-UNNAMED -jar target/matching-ws-*-exec.jar --mode=INDEX_AND_RUN \
  --clb.dataset.id=3LXRC --clb.user=*** --clb.password=*** --index.path=/tmp/index
```

### Name parsing

`life.catalogue.matching.util.NameParsers` wraps the shared parser. Since name-parser 5.0.0 there is
no pure-Java implementation, no parser cache/timeout and no `ParserConfig` to load from
ChecklistBank. `parse()` returns a `ParseResult` — `Parsed`, `Informal` or `Unparsable` — instead of
throwing; use `NameParsers.parseOrNull(..)` when you just want a `ParsedName` or null.

The cdylib ships in per-platform classifier JARs (`native/<os.detected.classifier>/`). The pom pins
`linux-x86_64` + `linux-aarch_64` so one `-exec.jar` works in both docker images, and a mac profile
adds the build host's own. **The libs are glibc-linked and there is no musl build**, so every image
here has to stay on a glibc base — an alpine base fails at `dlopen`.

## Architecture

This is a **Lucene-based taxonomic name matching service** that indexes scientific names from a checklist (typically from [ChecklistBank](https://www.checklistbank.org)) and exposes REST APIs to match occurrence data against those taxa.

### Two-phase design

1. **Index phase** (`IndexingService`): Reads name usages from a ChecklistBank PostgreSQL DB or a CSV file, builds three Lucene index directories under `--index.path`:
   - `/main` — the primary taxonomy index
   - `/identifiers` — optional identifier indexes (WoRMS LSIDs, etc.) for matching `taxonID`/`taxonConceptID`/`scientificNameID` DwC fields
   - `/ancillary` — optional status indexes (e.g. IUCN conservation status)

2. **Match phase** (`MatchingService` + `DatasetIndex`): Given a scientific name and optional higher classification, queries the Lucene index using `ScientificNameAnalyzer`, scores candidates by name similarity and authorship, then ranks results by confidence.

### Key classes

| Class | Role |
|-------|------|
| `Main` | CLI entry point (JCommander), launches Spring Boot with `web` profile |
| `MatchingApplication` | Spring Boot orchestrator; decides whether to build index, load configs, start web |
| `MatchingService` | Core matching logic: fuzzy name lookup, authorship/rank scoring, classification-based disambiguation |
| `DatasetIndex` | Lucene index wrapper: low-level queries and document retrieval |
| `IndexingService` | Builds/updates all three index types from DB or CSV |
| `ScientificNameAnalyzer` | Custom Lucene analyzer with scientific name normalization filters |
| `MatchController` / `MatchV1Controller` | REST endpoints at `/v2/species/match` and `/v1/species/match` |
| `IDController` | Identifier/taxonID lookup endpoints |
| `DatasetMapper` | MyBatis mapper for ChecklistBank PostgreSQL queries |

### Index structure

Each index directory contains the Lucene files plus a `metadata.json` with dataset key, title, alias, and taxon count. The root `datasets.json` resource maps URL prefixes (e.g. `http://marinespecies.org/...`) to canonical LSID prefixes for identifier matching.

### Dynamic logging

Adjust log levels at runtime without restart via Spring Boot Actuator:
```bash
curl -i -X POST -H 'Content-Type: application/json' \
  -d '{"configuredLevel": "INFO"}' \
  http://localhost:8080/actuator/loggers/life.catalogue.matching.controller
```
