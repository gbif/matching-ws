package life.catalogue.matching.model;

import lombok.extern.jackson.Jacksonized;
import org.gbif.nameparser.api.Rank;

import java.util.Set;
import java.util.stream.Collectors;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;

import static life.catalogue.matching.util.CleanupUtils.*;

@Schema(description = "A single name matching query")
@Jacksonized
@AllArgsConstructor
@Builder
public class NameUsageQuery {

  @Schema(description = "Usage key to look up directly. When provided, all other fields are ignored.")
  final public String usageKey;

  @Schema(description = "Darwin Core taxonID to match. Takes precedence over scientificName when provided.")
  final public String taxonID;

  @Schema(description = "Darwin Core taxonConceptID to match. Takes precedence over scientificName when provided.")
  final public String taxonConceptID;

  @Schema(description = "Darwin Core scientificNameID to match. Takes precedence over scientificName when provided.")
  final public String scientificNameID;

  @Schema(description = "Scientific name to fuzzy match against. May include authorship and year.")
  final public String scientificName;

  @Schema(description = "Scientific name authorship to match against.")
  final public String authorship;

  @Schema(description = "Generic part of the name when provided as atomised parts instead of the full scientificName.")
  final public String genericName;

  @Schema(description = "Specific epithet when provided as atomised parts.")
  final public String specificEpithet;

  @Schema(description = "Infraspecific epithet when provided as atomised parts.")
  final public String infraSpecificEpithet;

  @Schema(description = "Taxonomic rank filter.")
  final public Rank rank;

  @Schema(description = "Higher classification context used to disambiguate matches.")
  final public ClassificationQuery classification;

  @Schema(description = "Usage keys to exclude from the match results.")
  final public Set<String> exclude;

  @Schema(description = "If true, only matches the given name without falling back to higher classification.")
  final public Boolean strict;

  @Schema(description = "If true, includes alternative matches that were considered but rejected.")
  final public Boolean verbose;

  public static NameUsageQuery create(
    String usageKey,
    String taxonID,
    String taxonConceptID,
    String scientificNameID,
    String scientificName,
    String scientificNameAuthorship,
    String genericName,
    String specificEpithet,
    String infraspecificEpithet,
    String taxonRank,
    String verbatimTaxonRank,
    ClassificationQuery classification,
    Set<String> exclude,
    Boolean strict,
    Boolean verbose
  ){
    return new NameUsageQuery(
      removeNulls(usageKey),
      removeNulls(taxonID),
      removeNulls(taxonConceptID),
      removeNulls(scientificNameID),
      scientificName,
      scientificNameAuthorship,
      removeNulls(genericName),
      removeNulls(specificEpithet),
      removeNulls(infraspecificEpithet),
      parseRank(first(removeNulls(taxonRank), removeNulls(verbatimTaxonRank))),
      clean(classification),
      exclude != null ? exclude.stream().map(Object::toString).collect(Collectors.toSet()) : Set.of(),
      bool(strict),
      bool(verbose));
  }
}
