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

```bash
# Run with an existing index
java -jar target/matching-ws-*-exec.jar --mode=RUN --index.path=/tmp/index

# Build index from CSV then serve
java -jar target/matching-ws-*-exec.jar --mode=INDEX_CSV --index.path=/tmp/index --export.path=/tmp/export/

# Build index from ChecklistBank DB then serve
java -jar target/matching-ws-*-exec.jar --mode=INDEX_AND_RUN \
  --clb.dataset.id=3LXRC --clb.user=*** --clb.password=*** --index.path=/tmp/index
```

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
