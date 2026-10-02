package life.catalogue.matching.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The types of index held by the matching service.
 */
@Schema(description = "The types of index held by the matching service")
public enum IndexType {
  /** The primary taxonomy index, of which there is exactly one. */
  MAIN,
  /** Indexes of identifiers from other checklists, joined to the main index. */
  IDENTIFIER,
  /** Indexes of additional information (e.g. IUCN threat status), joined to the main index. */
  ANCILLARY
}
