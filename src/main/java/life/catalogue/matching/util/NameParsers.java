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
   * Parses a name into a complete scientific name, returning null for anything else.
   *
   * <p>Informal and placeholder names such as "Abies sp." or "Asteraceae incertae sedis" do parse,
   * but their canonical name is just the taxon they hang off, which would put them on par with that
   * real taxon. They are therefore returned as null too, so callers keep the verbatim name. Queries
   * that want the anchor use {@link #parsedName(ParseResult)} and {@link ParsedName#anchor()}.
   *
   * @return the parsed name, or null if the name is unparsable - a hybrid formula, an identifier
   *     such as a BOLD BIN or any other non name - or if it is informal or a placeholder
   */
  @Nullable
  public static ParsedName parseOrNull(
      @Nullable String scientificName, @Nullable Rank rank, @Nullable NomCode code) {
    ParsedName pn = parsedName(parse(scientificName, rank, code));
    if (pn != null && (pn.getType() == NameType.INFORMAL || pn.getType() == NameType.PLACEHOLDER)) {
      return null;
    }
    return pn;
  }

  /**
   * Since name-parser 6 informal names and placeholders with an anchor ("Chromista incertae sedis")
   * are parsed names of type INFORMAL or PLACEHOLDER, so this is just the parsed name if there is
   * one.
   *
   * @return the parsed name, or null for an unparsable result
   */
  @Nullable
  public static ParsedName parsedName(ParseResult result) {
    return result.parsed().orElse(null);
  }
}
