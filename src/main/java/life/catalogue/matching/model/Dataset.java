package life.catalogue.matching.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A dataset representing a source of taxonomic data.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Dataset {
  Integer clbKey;
  String datasetKey;
  String title;
  String alias;
  List<String> recognisedPrefixes = List.of();
  String indexedPrefix = "";
  String prefixToAddToOutput = "";
  Boolean removePrefixForMatching = false;
  Long taxonCount = 0L;
  Long matchesToMainIndex = 0L;
}
