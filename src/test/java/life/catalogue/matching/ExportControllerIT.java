package life.catalogue.matching;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Boots the web context over the in-memory test index and downloads exports over HTTP, checking
 * the response headers and that the body is a readable zip archive.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = OpenApiDocsIT.TestApp.class,
    properties = {
      "spring.cloud.zookeeper.enabled=false",
      "spring.cloud.zookeeper.discovery.enabled=false",
      "spring.cloud.zookeeper.config.enabled=false",
      "spring.cloud.service-registry.auto-registration.enabled=false",
      "spring.boot.admin.client.enabled=false",
      "spring.main.banner-mode=off"
    })
public class ExportControllerIT {

  @Autowired TestRestTemplate rest;

  @Test
  public void mainIndexIsDownloadable() throws Exception {
    ResponseEntity<byte[]> res = rest.getForEntity("/v2/species/match/export/main", byte[].class);
    assertEquals(HttpStatus.OK, res.getStatusCode());
    assertEquals("application/zip", String.valueOf(res.getHeaders().getContentType()));
    assertEquals(
        "main-index-export.zip", res.getHeaders().getContentDisposition().getFilename());

    assertNotNull(res.getBody());
    Map<String, List<String[]>> files = ExportServiceTest.readZip(res.getBody());
    assertTrue(files.containsKey("main.csv"), "no main.csv in " + files.keySet());
    List<String[]> rows = files.get("main.csv");
    assertTrue(rows.size() > 1, "main.csv has no rows");
    assertEquals("id", rows.get(0)[0]);
  }

  /** The test index has no identifier or ancillary indexes. */
  @Test
  public void missingIndexesAreNotFound() {
    for (String type : List.of("identifiers", "ancillary")) {
      ResponseEntity<String> res =
          rest.getForEntity("/v2/species/match/export/" + type, String.class);
      assertEquals(HttpStatus.NOT_FOUND, res.getStatusCode(), type);
    }
  }

  @Test
  public void exportsAreDocumented() throws Exception {
    ResponseEntity<String> res = rest.getForEntity("/v3/api-docs", String.class);
    assertEquals(HttpStatus.OK, res.getStatusCode());
    JsonNode paths = new ObjectMapper().readTree(res.getBody()).get("paths");
    for (String type : List.of("main", "identifiers", "ancillary")) {
      JsonNode get = paths.path("/v2/species/match/export/" + type).path("get");
      assertTrue(
          get.path("responses").path("200").path("content").has("application/zip"),
          type + " export is not documented as a zip download");
    }
  }
}
