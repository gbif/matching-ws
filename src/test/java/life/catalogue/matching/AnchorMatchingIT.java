package life.catalogue.matching;

import life.catalogue.api.vocab.MatchType;
import life.catalogue.api.vocab.TaxonomicStatus;
import life.catalogue.matching.index.DatasetIndex;
import life.catalogue.matching.model.NameUsage;
import life.catalogue.matching.model.NameUsageMatch;
import life.catalogue.matching.service.IndexingService;
import life.catalogue.matching.service.MatchingService;
import life.catalogue.matching.util.Dictionaries;

import org.gbif.nameparser.api.Rank;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Informal names and placeholders like "Abies sp." or "Asteraceae incertae sedis" are indexed with their
 * verbatim name, while queries for them match the taxon they hang off as a higher rank match.
 * Also covers the probable author comparisons that came with CoL 1.7.
 */
public class AnchorMatchingIT {

  private static DatasetIndex index;
  private static MatchingService matcher;

  @BeforeAll
  public static void buildMatcher() throws IOException {
    List<NameUsage> usages = List.of(
      usage("k1", null, "Plantae", null, Rank.KINGDOM),
      usage("p1", "k1", "Tracheophyta", null, Rank.PHYLUM),
      usage("c1", "p1", "Pinopsida", null, Rank.CLASS),
      usage("o1", "c1", "Pinales", null, Rank.ORDER),
      usage("f1", "o1", "Cupressaceae", null, Rank.FAMILY),
      usage("g1", "f1", "Biota", "D.Don ex Endl.", Rank.GENUS),
      usage("f2", "o1", "Pinaceae", null, Rank.FAMILY),
      usage("g2", "f2", "Abies", "Mill.", Rank.GENUS),
      usage("s2", "g2", "Abies alba", "Mill.", Rank.SPECIES),
      // an informal name in the checklist, like the Backbone's "Zopheridae fam"
      usage("x2", "g2", "Abies sp.", null, Rank.SPECIES),
      usage("c2", "p1", "Magnoliopsida", null, Rank.CLASS),
      usage("o2", "c2", "Asterales", null, Rank.ORDER),
      usage("f3", "o2", "Asteraceae", null, Rank.FAMILY),
      // a placeholder in the checklist
      usage("x3", "o2", "Asteraceae incertae sedis", null, Rank.FAMILY),
      usage("c3", "p1", "Liliopsida", null, Rank.CLASS),
      usage("o3", "c3", "Poales", null, Rank.ORDER),
      usage("f4", "o3", "Poaceae", null, Rank.FAMILY),
      usage("g4", "f4", "Festuca", "L.", Rank.GENUS),
      usage("s4", "g4", "Festuca rubra", "L.", Rank.SPECIES),
      usage("k2", null, "Animalia", null, Rank.KINGDOM),
      usage("p2", "k2", "Arthropoda", null, Rank.PHYLUM),
      usage("c4", "p2", "Insecta", null, Rank.CLASS),
      usage("o4", "c4", "Coleoptera", null, Rank.ORDER),
      usage("f5", "o4", "Carabidae", null, Rank.FAMILY),
      usage("g5", "f5", "Carabus", "Linnaeus, 1758", Rank.GENUS),
      // three Carabus nemoralis homonyms to tell the author comparisons apart
      usage("s5", "g5", "Carabus nemoralis", "(Linnaeus, 1758) Müller, 1764", Rank.SPECIES),
      usage("s6", "g5", "Carabus nemoralis", "Smith, 1801", Rank.SPECIES),
      usage("g6", "f5", "Pterostichus", "Bonelli, 1810", Rank.GENUS),
      usage("s7", "g6", "Pterostichus niger", "(Kirby, 1837)", Rank.SPECIES),
      usage("s8", "g6", "Pterostichus niger", null, Rank.SPECIES)
    );
    index = DatasetIndex.newDatasetIndex(IndexingService.newMemoryIndex(usages));
    matcher = new MatchingService(index, MatchingTestConfiguration.provideSynonyms(), Dictionaries.createDefault());
  }

  private static NameUsage usage(String id, String parentId, String name, String authorship, Rank rank) {
    return NameUsage.builder()
      .id(id)
      .parentId(parentId)
      .scientificName(authorship == null ? name : name + " " + authorship)
      .authorship(authorship)
      .status(TaxonomicStatus.ACCEPTED.toString())
      .rank(rank.toString())
      .build();
  }

  private static NameUsageMatch match(String name) {
    return matcher.match(name, null, null, false);
  }

  private static void assertHigherMatch(String name, String expectedKey) {
    NameUsageMatch m = match(name);
    assertNotNull(m.getUsage(), name + " should match");
    assertEquals(expectedKey, m.getUsage().getKey(), name);
    assertEquals(MatchType.HIGHERRANK, m.getDiagnostics().getMatchType(), name);
  }

  @Test
  public void placeholdersMatchTheirAnchorAsHigherRank() {
    assertHigherMatch("Asteraceae incertae sedis", "f3");
    assertHigherMatch("Abies unidentified", "g2");
  }

  @Test
  public void informalNamesMatchTheirAnchorAsHigherRank() {
    assertHigherMatch("Abies sp.", "g2");
    assertHigherMatch("Abies cf. alba", "g2");
    assertHigherMatch("Festuca rubra/arenaria", "g4");
    assertHigherMatch("Coleoptera sp. BOLD:AAV0432", "o4");
  }

  /**
   * Biota is the root of the CoL, but only a conifer genus in the indexes.
   */
  @Test
  public void biotaIsNoAnchor() {
    NameUsageMatch m = match("Biota incertae sedis");
    assertEquals(MatchType.NONE, m.getDiagnostics().getMatchType());
    assertNull(m.getUsage());
    // the genus itself still matches
    assertEquals("g1", match("Biota").getUsage().getKey());
  }

  /**
   * Strict matching never goes to a higher rank.
   */
  @Test
  public void strictIgnoresAnchors() {
    NameUsageMatch m = matcher.match("Asteraceae incertae sedis", null, null, true);
    assertNotEquals(MatchType.HIGHERRANK, m.getDiagnostics().getMatchType());
    assertNotEquals("f3", m.getUsage() == null ? null : m.getUsage().getKey());
  }

  /**
   * Informal names and placeholders in a checklist keep their verbatim name in the index,
   * so they do not compete with the real taxon they hang off.
   */
  @Test
  public void indexKeepsVerbatimNames() {
    assertEquals("Abies sp.", index.matchByUsageKey("x2").getUsage().getCanonicalName());
    assertEquals("Asteraceae incertae sedis", index.matchByUsageKey("x3").getUsage().getCanonicalName());

    NameUsageMatch m = match("Abies");
    assertEquals("g2", m.getUsage().getKey());
    assertEquals(MatchType.EXACT, m.getDiagnostics().getMatchType());
    m = match("Asteraceae");
    assertEquals("f3", m.getUsage().getKey());
    assertEquals(MatchType.EXACT, m.getDiagnostics().getMatchType());
  }

  /**
   * Citing only the basionym author is probably the same name and beats a different author.
   */
  @Test
  public void probablyEqualAuthor() {
    assertEquals("s5", match("Carabus nemoralis Linnaeus, 1758").getUsage().getKey());
  }

  /**
   * A different original author is probably a different name, worse than a name without any authorship.
   */
  @Test
  public void probablyDifferentAuthor() {
    assertEquals("s8", match("Pterostichus niger Harrington, 1899").getUsage().getKey());
  }
}
