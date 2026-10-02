package life.catalogue.matching.service;

import life.catalogue.matching.index.DatasetIndex;
import life.catalogue.matching.model.Dataset;
import life.catalogue.matching.model.IndexType;
import life.catalogue.matching.model.StoredClassification;
import life.catalogue.matching.model.StoredName;
import life.catalogue.matching.util.IOUtil;

import org.gbif.nameparser.api.Rank;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.MultiBits;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.util.Bits;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.opencsv.CSVWriterBuilder;
import com.opencsv.ICSVWriter;

import lombok.extern.slf4j.Slf4j;

import static life.catalogue.matching.util.IndexConstants.*;

/**
 * Provides zipped CSV exports of the content of the main, identifier and ancillary indexes.
 *
 * <p>Exports are streamed: each stored document is read from the index, written as a CSV row and
 * compressed straight onto the supplied output stream. Nothing is written to disk and memory use is
 * independent of the size of the index.
 */
@Slf4j
@Service
public class ExportService {

  /** How often (in rows) to check that the client is still reading the export. */
  private static final int ERROR_CHECK_INTERVAL = 10_000;

  /** The ranks of the higher classification that are flattened into columns of the main export. */
  private static final List<Rank> CLASSIFICATION_RANKS =
    List.of(Rank.KINGDOM, Rank.PHYLUM, Rank.CLASS, Rank.ORDER, Rank.FAMILY, Rank.GENUS, Rank.SPECIES);

  /** CSV column name to stored lucene field, shared by all index types. */
  private static final Map<String, String> NAME_USAGE_COLUMNS = new LinkedHashMap<>();

  static {
    NAME_USAGE_COLUMNS.put("id", FIELD_ID);
    NAME_USAGE_COLUMNS.put("parentId", FIELD_PARENT_ID);
    NAME_USAGE_COLUMNS.put("acceptedId", FIELD_ACCEPTED_ID);
    NAME_USAGE_COLUMNS.put("scientificName", FIELD_SCIENTIFIC_NAME);
    NAME_USAGE_COLUMNS.put("canonicalName", FIELD_CANONICAL_NAME);
    NAME_USAGE_COLUMNS.put("authorship", FIELD_AUTHORSHIP);
    NAME_USAGE_COLUMNS.put("rank", FIELD_RANK);
    NAME_USAGE_COLUMNS.put("status", FIELD_STATUS);
    NAME_USAGE_COLUMNS.put("code", FIELD_NOMENCLATURAL_CODE);
    NAME_USAGE_COLUMNS.put("type", FIELD_TYPE);
    NAME_USAGE_COLUMNS.put("genericName", FIELD_GENERICNAME);
    NAME_USAGE_COLUMNS.put("infragenericEpithet", FIELD_INFRAGENERIC_EPITHET);
    NAME_USAGE_COLUMNS.put("specificEpithet", FIELD_SPECIFIC_EPITHET);
    NAME_USAGE_COLUMNS.put("infraspecificEpithet", FIELD_INFRASPECIFIC_EPITHET);
  }

  private final DatasetIndex datasetIndex;
  private final IOUtil ioUtil;

  public ExportService(DatasetIndex datasetIndex, IOUtil ioUtil) {
    this.datasetIndex = datasetIndex;
    this.ioUtil = ioUtil;
  }

  /**
   * A zip archive that is ready to be streamed.
   *
   * @param fileName a suggested file name for the archive
   * @param body writes the archive
   */
  public record ZipExport(String fileName, Body body) {

    @FunctionalInterface
    public interface Body {
      void writeTo(OutputStream out) throws IOException;
    }

    /** Streams the archive to the supplied stream, which is closed on completion. */
    public void writeTo(OutputStream out) throws IOException {
      body.writeTo(out);
    }
  }

  /**
   * Prepares a zipped CSV export of the indexes of the given type. The archive holds one CSV file
   * per index.
   *
   * <p>The indexes to export are resolved here, before anything is written, so that a missing
   * index is reported as an error rather than as an empty or truncated archive.
   *
   * @param type the type of index to export
   * @param datasetKey optionally restricts the export to the index of a single dataset, identified
   *     by its ChecklistBank key or GBIF dataset key. Ignored if null or blank.
   * @return the export, ready to be streamed
   * @throws ResponseStatusException 503 if the indexes are not loaded, 404 if there is no matching
   *     index
   */
  public ZipExport export(IndexType type, String datasetKey) {
    if (!datasetIndex.getIsInitialised()) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Index not loaded");
    }

    final boolean filtered = datasetKey != null && !datasetKey.isBlank();
    Map<Dataset, IndexReader> readers = new LinkedHashMap<>(datasetIndex.getIndexReaders(type));
    if (filtered) {
      readers.keySet().removeIf(dataset -> !hasKey(dataset, datasetKey.trim()));
    }
    if (readers.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND,
        "No " + type.name().toLowerCase() + " index available" + (filtered ? " for dataset " + datasetKey : ""));
    }

    // name the CSV files up front, so that the names are unique within the archive
    Map<String, IndexReader> csvFiles = new LinkedHashMap<>();
    readers.forEach((dataset, reader) -> csvFiles.put(csvFileName(type, dataset, csvFiles.size()), reader));

    String fileName = type.name().toLowerCase()
      + (filtered ? "-" + sanitise(datasetKey.trim()) : "")
      + "-index-export.zip";
    return new ZipExport(fileName, out -> writeZip(type, csvFiles, out));
  }

  private void writeZip(IndexType type, Map<String, IndexReader> csvFiles, OutputStream out) throws IOException {
    final List<Column> columns = columns(type);
    try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out), StandardCharsets.UTF_8)) {
      for (Map.Entry<String, IndexReader> csvFile : csvFiles.entrySet()) {
        zip.putNextEntry(new ZipEntry(csvFile.getKey()));
        // the writer is flushed but deliberately not closed, as that would close the archive
        ICSVWriter csv = new CSVWriterBuilder(new OutputStreamWriter(zip, StandardCharsets.UTF_8)).build();
        long rows = writeCsv(csvFile.getValue(), columns, csv);
        zip.closeEntry();
        log.info("Exported {} rows of the {} index to {}", rows, type, csvFile.getKey());
      }
    }
  }

  /**
   * Writes all the live documents of an index as CSV rows.
   *
   * @return the number of rows written, excluding the header
   */
  private long writeCsv(IndexReader reader, List<Column> columns, ICSVWriter csv) throws IOException {
    csv.writeNext(columns.stream().map(Column::name).toArray(String[]::new), false);

    final Bits liveDocs = MultiBits.getLiveDocs(reader);
    final StoredFields storedFields = reader.storedFields();
    final String[] row = new String[columns.size()];
    long rows = 0;

    for (int docId = 0; docId < reader.maxDoc(); docId++) {
      if (liveDocs != null && !liveDocs.get(docId)) {
        continue; // deleted
      }
      Document doc = storedFields.document(docId);
      Map<String, StoredName> classification = null;
      for (int i = 0; i < row.length; i++) {
        Column column = columns.get(i);
        if (column.rank() == null) {
          row[i] = doc.get(column.field());
        } else {
          if (classification == null) {
            classification = classification(doc);
          }
          StoredName name = classification.get(column.rank());
          row[i] = name == null ? null : (column.key() ? name.getKey() : name.getName());
        }
      }
      csv.writeNext(row, false);

      // opencsv swallows write errors, so check for them to stop reading the index once the
      // client has gone away
      if (++rows % ERROR_CHECK_INTERVAL == 0) {
        failOnError(csv);
      }
    }
    csv.flush();
    failOnError(csv);
    return rows;
  }

  private static void failOnError(ICSVWriter csv) throws IOException {
    if (csv.checkError()) {
      IOException e = csv.getException();
      throw e != null ? e : new IOException("Failed to write CSV export");
    }
  }

  /** The higher classification of a document keyed by upper case rank, empty if it has none. */
  private Map<String, StoredName> classification(Document doc) {
    Map<String, StoredName> byRank = new LinkedHashMap<>();
    ioUtil.deserialiseField(doc, FIELD_CLASSIFICATION, StoredClassification.class)
      .map(StoredClassification::getNames)
      .ifPresent(names -> names.forEach(name -> {
        if (name.getRank() != null) {
          byRank.putIfAbsent(name.getRank().toUpperCase(), name);
        }
      }));
    return byRank;
  }

  /**
   * A column of an export, populated either from a stored field or, if a rank is given, from the
   * name (or key) of the taxon at that rank in the higher classification.
   */
  private record Column(String name, String field, String rank, boolean key) {}

  private static List<Column> columns(IndexType type) {
    Map<String, String> fields = new LinkedHashMap<>(NAME_USAGE_COLUMNS);
    if (type == IndexType.MAIN) {
      fields.put("formattedName", FIELD_FORMATTED);
      fields.put("left", FIELD_LEFT_NESTED_SET_ID);
      fields.put("right", FIELD_RIGHT_NESTED_SET_ID);
    } else {
      // the usage in the main index that this record was matched to
      fields.put("mainIndexId", FIELD_JOIN_ID);
    }
    if (type == IndexType.ANCILLARY) {
      fields.put("category", FIELD_CATEGORY);
    }

    List<Column> columns = new ArrayList<>();
    fields.forEach((name, field) -> columns.add(new Column(name, field, null, false)));
    if (type == IndexType.MAIN) {
      for (Rank rank : CLASSIFICATION_RANKS) {
        String name = rank.name().toLowerCase();
        columns.add(new Column(name, null, rank.name(), false));
        columns.add(new Column(name + "Key", null, rank.name(), true));
      }
    }
    return columns;
  }

  private static boolean hasKey(Dataset dataset, String key) {
    return key.equals(dataset.getDatasetKey())
      || (dataset.getClbKey() != null && key.equals(dataset.getClbKey().toString()));
  }

  private static String csvFileName(IndexType type, Dataset dataset, int position) {
    if (type == IndexType.MAIN) {
      return MAIN_INDEX_DIR + ".csv";
    }
    String key = dataset.getClbKey() != null ? dataset.getClbKey().toString()
      : dataset.getDatasetKey() != null ? dataset.getDatasetKey() : String.valueOf(position + 1);
    return type.name().toLowerCase() + "-" + sanitise(key) + ".csv";
  }

  /** Makes a user supplied or configured value safe to use in a file name or HTTP header. */
  private static String sanitise(String value) {
    return value.replaceAll("[^A-Za-z0-9._-]", "_");
  }
}
