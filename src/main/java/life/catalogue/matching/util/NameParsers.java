package life.catalogue.matching.util;

import org.gbif.nameparser.api.NameParser;
import org.gbif.nameparser.api.NameType;
import org.gbif.nameparser.api.NomCode;
import org.gbif.nameparser.api.ParseResult;
import org.gbif.nameparser.api.ParsedName;
import org.gbif.nameparser.api.Rank;
import org.gbif.nameparser.rust.NameParserRust;

import javax.annotation.Nullable;

import lombok.extern.slf4j.Slf4j;

/**
 * Utility class to provide a shared NameParser instance.
 *
 * <p>Backed by the rust parser, the only implementation since name-parser 5.0.0. It parses
 * in-process over FFM, is thread safe and stateless - there is no timeout, no cache and no parser
 * config to load anymore.
 */
@Slf4j
public class NameParsers {
  public static final NameParser INSTANCE = new NameParserRust();

  private NameParsers() {}

  /**
   * Parses a name, tolerating a null one. The rust parser marshals its input across the FFM
   * boundary and throws a NullPointerException on a null name, while we do get called without one -
   * a match request may carry nothing but a taxonID.
   *
   * @return the parse result, OTHER for a null name just as for any other non name
   */
  public static ParseResult parse(
      @Nullable String scientificName, @Nullable Rank rank, @Nullable NomCode code) {
    if (scientificName == null) {
      return new ParseResult.Unparsable(NameType.OTHER, code, "");
    }
    return INSTANCE.parse(scientificName, null, rank, code);
  }

  /**
   * Parses a name, returning null instead of throwing for names that have no structure.
   *
   * @return the parsed name, or null if the name is unparsable - a hybrid formula, an identifier
   *     such as a BOLD BIN, a placeholder or any other non name
   */
  @Nullable
  public static ParsedName parseOrNull(
      @Nullable String scientificName, @Nullable Rank rank, @Nullable NomCode code) {
    return parsedName(parse(scientificName, rank, code));
  }

  /**
   * Flattens a parse result into a ParsedName the way the parser did before 5.0.0 split informal
   * names off into their own result variant. Informal names such as "Abies spec." carry no
   * ParsedName of their own but rebuild into the equivalent INFORMAL one, which keeps the taxon
   * anchor we want to match against.
   *
   * @return the parsed name, or null for an unparsable result
   */
  @Nullable
  public static ParsedName parsedName(ParseResult result) {
    if (result instanceof ParseResult.Informal informal) {
      return informal.toParsedName();
    }
    return result.parsed().orElse(null);
  }
}
