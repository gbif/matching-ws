package life.catalogue.matching;

import life.catalogue.api.vocab.TaxonomicStatus;
import life.catalogue.matching.index.DatasetIndex;
import life.catalogue.matching.model.*;
import life.catalogue.matching.service.IndexingService;
import life.catalogue.matching.service.MatchingService;
import life.catalogue.matching.util.Dictionaries;

import org.gbif.nameparser.api.Rank;

import java.io.IOException;
import java.util.List;

import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ID matching
 */
public class IDMatchingIT {

  private static MatchingService matcher;

  @BeforeAll
  public static void buildMatcher() throws IOException {

    // create the main datasetIndex
    final DatasetIndex datasetIndex = MatchingTestConfiguration.provideIndex();
    matcher =
      new MatchingService(datasetIndex, MatchingTestConfiguration.provideSynonyms(), Dictionaries.createDefault());

    // create the join index
    List<NameUsage> usages = List.of(
     NameUsage.builder().id("ext-1").scientificName( "Animalia").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.KINGDOM.toString()).build(),
     NameUsage.builder().id("ext-2").parentId("ext-1").scientificName("Arthropoda").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.PHYLUM.toString()).build(),
     NameUsage.builder().id("ext-3").parentId("ext-2").scientificName("Callipodida").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.ORDER.toString()).build(),
     NameUsage.builder().id("ext-4").parentId("ext-3").scientificName("Abacionidae").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.FAMILY.toString()).build(),
     NameUsage.builder().id("ext-5").parentId("ext-4").scientificName("Abacion").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.GENUS.toString()).build(),
     NameUsage.builder().id("ext-6").parentId("ext-5").scientificName("Abacion tesselatum").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.SPECIES.toString()).build(),
     NameUsage.builder().id("ext-7").parentId("ext-6").scientificName("Abacion tesselatum iamnew").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.SPECIES.toString()).build(),
     // a plant branch ending in a name that is a synonym in the main index
     NameUsage.builder().id("ext-8").scientificName("Plantae").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.KINGDOM.toString()).build(),
     NameUsage.builder().id("ext-9").parentId("ext-8").scientificName("Magnoliophyta").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.PHYLUM.toString()).build(),
     NameUsage.builder().id("ext-10").parentId("ext-9").scientificName("Liliopsida").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.CLASS.toString()).build(),
     NameUsage.builder().id("ext-11").parentId("ext-10").scientificName("Poales").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.ORDER.toString()).build(),
     NameUsage.builder().id("ext-12").parentId("ext-11").scientificName("Poaceae").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.FAMILY.toString()).build(),
     NameUsage.builder().id("ext-13").parentId("ext-12").scientificName("Elytrigia repens (L.) Desv.").status(TaxonomicStatus.SYNONYM.toString()).rank(Rank.SPECIES.toString()).build()
    );

    Directory tempJoinIndex = IndexingService.newMemoryIndex(usages);
    Directory joinIndex = new ByteBuffersDirectory();

    Long[] counts = IndexingService.createJoinIndex(
      matcher,
      tempJoinIndex,
      joinIndex,
      false,
      false,
      false,
      1,
      100
    );

    Dataset dataset = Dataset.builder().build();
    dataset.setClbKey(1);
    dataset.setAlias("DUMMY_IDS");
    dataset.setTitle("Dummy dataset for testing");
    dataset.setIndexedPrefix("");
    dataset.setRecognisedPrefixes(List.of("ext-", "other-ext-", "other-ext2-"));
    dataset.setTaxonCount(counts[0]);
    dataset.setMatchesToMainIndex(counts[1]);
    dataset.setRemovePrefixForMatching(false);
    datasetIndex.initWithIdentifierDir(dataset, joinIndex);
  }

  // Tests
  @Test
  public void testJoinHigherTaxa(){

    NameUsageMatch match = matcher.match(new NameUsageQuery(
      null, "ext-4", null, null, null, null,
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match);
    assertNotNull(match.getUsage());
    assertEquals("7228", match.getUsage().getKey());
    assertEquals("Abacionidae", match.getUsage().getName());
  }

  @Test
  public void testJoinLeafTaxa(){

    NameUsageMatch match1 = matcher.match(new NameUsageQuery(
      null, "ext-6", null, null, null, null,
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match1);
    assertNotNull(match1.getUsage());
    assertEquals("1011638", match1.getUsage().getKey());

    NameUsageMatch match2 = matcher.match(new NameUsageQuery(
      null, null, null, null, "Abacion tesselatum", null,
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match2);
    assertNotNull(match2.getUsage());
    assertEquals("1011638", match2.getUsage().getKey());
  }

  @Test
  public void testJoinLeafTaxaWithPrefix(){

    NameUsageMatch match1 = matcher.match(new NameUsageQuery(
      null, "other-ext-", null, null, "Abacion tesselatum", null,
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match1);
    assertNotNull(match1.getUsage());
    assertEquals("1011638", match1.getUsage().getKey());

    NameUsageMatch match2 = matcher.match(new NameUsageQuery(
      null, "other-ext2-", null, null, "Abacion tesselatum", null,
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match2);
    assertNotNull(match2.getUsage());
    assertEquals("1011638", match2.getUsage().getKey());
  }

  @Test
  public void testIDandNameInconsistent(){

    NameUsageMatch match3 = matcher.match(new NameUsageQuery(
      null, "ext-6", null, null, "Abacion nonsense", "Abacionidae",
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match3);
    assertNotNull(match3.getUsage());
    assertEquals("1011638", match3.getUsage().getKey());
    assertTrue(match3.getDiagnostics().getIssues().contains(MatchingIssue.SCIENTIFIC_NAME_AND_ID_INCONSISTENT));
  }

  @Test
  public void testTaxonIDNotFound(){

    NameUsageMatch match3 = matcher.match(new NameUsageQuery(
      null, "ext-123", null, null, "Abacion nonsense", "Abacionidae",
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match3);
    assertTrue(match3.getDiagnostics().getIssues().contains(MatchingIssue.TAXON_ID_NOT_FOUND));
  }

  @Test
  public void testTaxonConceptIDNotFound(){

    NameUsageMatch match3 = matcher.match(new NameUsageQuery(
      null, null, "ext-123", null, "Abacion nonsense", "Abacionidae",
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match3);
    assertTrue(match3.getDiagnostics().getIssues().contains(MatchingIssue.TAXON_CONCEPT_ID_NOT_FOUND));
  }

  @Test
  public void testScientificNameIDNotFound(){

    NameUsageMatch match3 = matcher.match(new NameUsageQuery(
      null, null, null, "ext-123", "Abacion nonsense", "Abacionidae",
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match3);
    assertTrue(match3.getDiagnostics().getIssues().contains(MatchingIssue.SCIENTIFIC_NAME_ID_NOT_FOUND));
  }


  @Test
  public void testTaxonIDIgnored(){

    NameUsageMatch match3 = matcher.match(new NameUsageQuery(
      null, "ext-7", null, null, "Abacion nonsense", "Abacionidae",
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match3);
    assertTrue(match3.getDiagnostics().getIssues().contains(MatchingIssue.TAXON_ID_NOT_FOUND));
  }

  @Test
  public void testTaxonConceptIDIgnored(){

    NameUsageMatch match3 = matcher.match(new NameUsageQuery(
      null, null, "ext-7", null, "Abacion nonsense", "Abacionidae",
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match3);
    assertTrue(match3.getDiagnostics().getIssues().contains(MatchingIssue.TAXON_CONCEPT_ID_NOT_FOUND));
  }

  @Test
  public void testScientificNameIDIgnored(){

    NameUsageMatch match3 = matcher.match(new NameUsageQuery(
      null, null, null, "ext-7", "Abacion nonsense", "Abacionidae",
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match3);
    assertTrue(match3.getDiagnostics().getIssues().contains(MatchingIssue.SCIENTIFIC_NAME_ID_NOT_FOUND));
  }

  @Test
  public void testTaxonNameAndIDAmbiguous(){

    NameUsageMatch match3 = matcher.match(new NameUsageQuery(
      null, null, null, "ext-6", "Abacion nonsense", "Abacionidae",
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match3);
    assertTrue(match3.getDiagnostics().getIssues().contains(MatchingIssue.TAXON_MATCH_NAME_AND_ID_AMBIGUOUS));
  }

  /**
   * An identifier whose name resolves to a synonym in the main index must match that synonym,
   * not the accepted usage it points to. See https://github.com/gbif/matching-ws/issues/16
   */
  @Test
  public void testJoinToSynonym(){

    NameUsageMatch match = matcher.match(new NameUsageQuery(
      null, "ext-13", null, null, null, null,
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match);
    assertNotNull(match.getUsage());
    assertEquals("7522774", match.getUsage().getKey(), "should match the synonym Elytrigia repens");
    assertEquals("Elytrigia repens", match.getUsage().getCanonicalName());
    assertTrue(match.isSynonym());
    assertNotNull(match.getAcceptedUsage());
    assertEquals("2705093", match.getAcceptedUsage().getKey());
  }

  /**
   * Supplying the synonym's own name alongside its identifier is consistent.
   */
  @Test
  public void testJoinToSynonymWithSynonymName(){

    NameUsageMatch match = matcher.match(new NameUsageQuery(
      null, "ext-13", null, null, "Elytrigia repens", null,
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match);
    assertEquals("7522774", match.getUsage().getKey());
    assertFalse(issues(match).contains(MatchingIssue.TAXON_MATCH_NAME_AND_ID_AMBIGUOUS));
    assertFalse(issues(match).contains(MatchingIssue.SCIENTIFIC_NAME_AND_ID_INCONSISTENT));
  }

  /**
   * Supplying the accepted name alongside a synonym identifier still denotes the same taxon
   * and must not be reported as ambiguous or inconsistent.
   */
  @Test
  public void testJoinToSynonymWithAcceptedName(){

    NameUsageMatch match = matcher.match(new NameUsageQuery(
      null, "ext-13", null, null, "Panicum repens", null,
      null,null,null, null, null, null,
      false, false
    ));

    assertNotNull(match);
    assertEquals("7522774", match.getUsage().getKey());
    assertFalse(issues(match).contains(MatchingIssue.TAXON_MATCH_NAME_AND_ID_AMBIGUOUS));
    assertFalse(issues(match).contains(MatchingIssue.SCIENTIFIC_NAME_AND_ID_INCONSISTENT));
  }

  private static List<MatchingIssue> issues(NameUsageMatch match) {
    return match.getDiagnostics().getIssues() == null ? List.of() : match.getDiagnostics().getIssues();
  }
}
