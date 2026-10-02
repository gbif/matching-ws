package life.catalogue.matching;

import life.catalogue.matching.index.DatasetIndex;
import life.catalogue.matching.model.IndexType;
import life.catalogue.matching.service.ExportService;
import life.catalogue.matching.service.IndexingService;
import life.catalogue.matching.util.IOUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.opencsv.CSVReader;

import static org.junit.jupiter.api.Assertions.*;

public class ExportServiceTest {

  private static DatasetIndex index;
  private static ExportService exportService;

  @BeforeAll
  public static void buildIndex() throws Exception {
    index = DatasetIndex.newDatasetIndex(IndexingService.newMemoryIndex(DatasetIndexTest.readTestNames()));
    exportService = new ExportService(index, new IOUtil());
  }

  /** Reads all the CSV files of a zip archive, keyed by file name. */
  static Map<String, List<String[]>> readZip(byte[] zipped) throws Exception {
    Map<String, List<String[]>> files = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(zipped), StandardCharsets.UTF_8)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        // not closed, as that would close the archive
        CSVReader reader = new CSVReader(new InputStreamReader(zip, StandardCharsets.UTF_8));
        files.put(entry.getName(), reader.readAll());
      }
    }
    return files;
  }

  @Test
  public void testExportMainIndex() throws Exception {
    ExportService.ZipExport export = exportService.export(IndexType.MAIN, null);
    assertEquals("main-index-export.zip", export.fileName());

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    export.writeTo(out);
    Map<String, List<String[]>> files = readZip(out.toByteArray());

    assertEquals(List.of("main.csv"), List.copyOf(files.keySet()));
    List<String[]> rows = files.get("main.csv");

    // a header, then a row for each document in the index
    int numDocs = index.getIndexReaders(IndexType.MAIN).values().iterator().next().numDocs();
    assertTrue(numDocs > 0);
    assertEquals(numDocs + 1, rows.size());

    List<String> header = Arrays.asList(rows.get(0));
    assertEquals("id", header.get(0));
    assertTrue(header.containsAll(List.of("scientificName", "rank", "status", "kingdom", "kingdomKey")));
    rows.forEach(row -> assertEquals(header.size(), row.length));

    // 7	Abies alba Mill. - see DatasetIndexTest
    String[] abiesAlba = rows.stream().filter(row -> "7".equals(row[0])).findFirst().orElseThrow();
    assertEquals("Abies alba Mill.", abiesAlba[header.indexOf("scientificName")]);
    assertEquals("SPECIES", abiesAlba[header.indexOf("rank")]);
  }

  @Test
  public void testExportMissingIndex() {
    ResponseStatusException e = assertThrows(ResponseStatusException.class,
      () -> exportService.export(IndexType.ANCILLARY, null));
    assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
  }

  @Test
  public void testExportUnknownDataset() {
    ResponseStatusException e = assertThrows(ResponseStatusException.class,
      () -> exportService.export(IndexType.IDENTIFIER, "not-a-dataset"));
    assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
  }
}
