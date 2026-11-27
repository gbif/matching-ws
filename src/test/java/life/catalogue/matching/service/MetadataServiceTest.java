package life.catalogue.matching.service;

import java.util.Map;

import life.catalogue.matching.model.APIMetadata;
import life.catalogue.matching.model.IndexMetadata;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class MetadataServiceTest {

  private static APIMetadata generated() {
    IndexMetadata main = new IndexMetadata();
    main.setClbDatasetKey("316691");
    main.setDatasetKey("7ddf754f-d193-4cc9-b351-99906754a03b");
    main.setDatasetAlias("COL26.10 XR");
    main.setDatasetTitle("Catalogue of Life");
    main.setSizeInMB(1234L);
    main.setNameUsageCount(8504545L);
    main.setNameUsageByRankCount(Map.of("SPECIES", 5000000L));

    APIMetadata m = new APIMetadata();
    m.setCreated("2026-11-19T14:17:15.229Z");
    m.setMainIndex(main);
    return m;
  }

  @Test
  public void overrideSingleMainIndexField() {
    IndexMetadata main = new IndexMetadata();
    main.setClbDatasetKey("100");
    APIMetadata overrides = new APIMetadata();
    overrides.setMainIndex(main);

    APIMetadata m = generated();
    MetadataService.applyOverrides(m, overrides);

    IndexMetadata idx = m.getMainIndex();
    assertEquals("100", idx.getClbDatasetKey());
    assertEquals("7ddf754f-d193-4cc9-b351-99906754a03b", idx.getDatasetKey());
    assertEquals("COL26.10 XR", idx.getDatasetAlias());
    assertEquals("Catalogue of Life", idx.getDatasetTitle());
    assertEquals(1234L, idx.getSizeInMB());
    assertEquals(8504545L, idx.getNameUsageCount());
    assertEquals(Map.of("SPECIES", 5000000L), idx.getNameUsageByRankCount());
    assertEquals("2026-11-19T14:17:15.229Z", m.getCreated());
  }

  @Test
  public void overrideSeveralMainIndexFields() {
    IndexMetadata main = new IndexMetadata();
    main.setDatasetAlias("COL-TEST");
    main.setNameUsageCount(42L);
    APIMetadata overrides = new APIMetadata();
    overrides.setMainIndex(main);

    APIMetadata m = generated();
    MetadataService.applyOverrides(m, overrides);

    IndexMetadata idx = m.getMainIndex();
    assertEquals("316691", idx.getClbDatasetKey());
    assertEquals("COL-TEST", idx.getDatasetAlias());
    assertEquals(42L, idx.getNameUsageCount());
    assertEquals(1234L, idx.getSizeInMB());
  }

  @Test
  public void noOverrides() {
    APIMetadata m = generated();
    MetadataService.applyOverrides(m, new APIMetadata());
    assertEquals(generated(), m);

    MetadataService.applyOverrides(m, null);
    assertEquals(generated(), m);
  }

  @Test
  public void mainIndexMissing() {
    IndexMetadata main = new IndexMetadata();
    main.setClbDatasetKey("100");
    APIMetadata overrides = new APIMetadata();
    overrides.setMainIndex(main);

    APIMetadata m = new APIMetadata();
    MetadataService.applyOverrides(m, overrides);
    assertEquals("100", m.getMainIndex().getClbDatasetKey());
  }
}
