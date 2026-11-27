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

    Directory joinIndex = new ByteBuffersDirectory();

    Long[] counts = IndexingService.createJoinIndex(
      matcher,
      usages,
      joinIndex,
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

    // create an IUCN like ancillary index, joined to accepted usages only
    Directory iucnIndex = new ByteBuffersDirectory();
    IndexingService.createJoinIndex(matcher, List.of(
      NameUsage.builder().id("iucn-1").scientificName("Panicum repens L.").status(TaxonomicStatus.ACCEPTED.toString()).rank(Rank.SPECIES.toString()).category("vulnerable").build()
    ), iucnIndex, true, true, 1, 100);
    Dataset iucn = Dataset.builder().build();
    iucn.setClbKey(2);
    iucn.setAlias("IUCN");
    datasetIndex.initWithAncillaryDir(iucn, iucnIndex);
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
   * Matching by an identifier must give the same result as matching by the name the identifier denotes.
   * For a synonym that is the synonym itself, not the accepted usage it points to.
   * See https://github.com/gbif/matching-ws/issues/16
   */
  @Test
  public void testJoinToSynonym(){

    NameUsageMatch byId = matcher.match(new NameUsageQuery(
      null, "ext-13", null, null, null, null,
      null,null,null, null, null, null,
      false, false
    ));
    NameUsageMatch byName = matcher.match(new NameUsageQuery(
      null, null, null, null, "Elytrigia repens (L.) Desv.", null,
      null,null,null, null, null, null,
      false, false
    ));

    assertEquals("7522774", byName.getUsage().getKey(), "name match should be the synonym Elytrigia repens (L.) Desv.");
    assertSameMatch(byName, byId);
    assertTrue(byId.isSynonym());
    assertEquals("2705093", byId.getAcceptedUsage().getKey());
  }

  /**
   * Same as {@link #testJoinToSynonym()} for an identifier of an accepted name.
   */
  @Test
  public void testIdMatchEqualsNameMatchForAccepted(){

    NameUsageMatch byId = matcher.match(new NameUsageQuery(
      null, "ext-6", null, null, null, null,
      null,null,null, null, null, null,
      false, false
    ));
    NameUsageMatch byName = matcher.match(new NameUsageQuery(
      null, null, null, null, "Abacion tesselatum", null,
      null,null,null, null, null, null,
      false, false
    ));

    assertSameMatch(byName, byId);
    assertFalse(byId.isSynonym());
  }

  /**
   * Ancillary status like IUCN is joined to accepted usages only,
   * so a synonym must carry the status of its accepted taxon, whether matched by ID or by name.
   */
  @Test
  public void testSynonymIucnStatus(){

    NameUsageMatch accepted = matcher.match(new NameUsageQuery(
      null, null, null, null, "Panicum repens", null,
      null,null,null, null, null, null,
      false, false
    ));
    assertEquals("2705093", accepted.getUsage().getKey());
    assertEquals("VULNERABLE", iucn(accepted));

    NameUsageMatch byName = matcher.match(new NameUsageQuery(
      null, null, null, null, "Elytrigia repens (L.) Desv.", null,
      null,null,null, null, null, null,
      false, false
    ));
    assertEquals("VULNERABLE", iucn(byName));

    NameUsageMatch byId = matcher.match(new NameUsageQuery(
      null, "ext-13", null, null, null, null,
      null,null,null, null, null, null,
      false, false
    ));
    assertEquals("VULNERABLE", iucn(byId));
  }

  private static void assertSameMatch(NameUsageMatch expected, NameUsageMatch actual) {
    assertNotNull(actual.getUsage());
    assertEquals(expected.getUsage(), actual.getUsage());
    assertEquals(expected.getAcceptedUsage(), actual.getAcceptedUsage());
    assertEquals(expected.isSynonym(), actual.isSynonym());
    assertEquals(expected.getClassification(), actual.getClassification());
    assertEquals(expected.getAdditionalStatus(), actual.getAdditionalStatus());
    assertEquals(expected.getEnvironments(), actual.getEnvironments());
  }

  private static String iucn(NameUsageMatch match) {
    return match.getAdditionalStatus().stream()
      .filter(s -> "IUCN".equals(s.getDatasetAlias()))
      .map(NameUsageMatch.Status::getStatus)
      .findFirst().orElse(null);
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
