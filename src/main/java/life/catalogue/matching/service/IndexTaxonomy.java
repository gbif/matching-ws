package life.catalogue.matching.service;

import life.catalogue.api.vocab.TaxonomicStatus;
import life.catalogue.matching.model.StoredClassification;
import life.catalogue.matching.model.StoredName;

import java.util.*;

import lombok.extern.slf4j.Slf4j;

/**
 * A compact, in-memory view of the tree of a checklist, used while building indexes so that parents,
 * children, classifications and nested set ranges are resolved with array lookups instead of a
 * Lucene query per hop.
 *
 * <p>Rows are addressed by their position in the source (CSV row order). A row is {@code null} (its
 * id is null) when it could not be converted into a document and is left out of the index.
 *
 * <p>Parent and accepted references keep the semantics of the stored Lucene fields: {@link #NONE} when
 * the field is absent, {@link #UNRESOLVED} when it is present but points to an id that is not in the
 * checklist. Ids are resolved to their first occurrence.
 */
@Slf4j
final class IndexTaxonomy {

  static final int NONE = -1;
  static final int UNRESOLVED = -2;

  /** Guards walks up the tree against cycles in the source data. */
  private static final int MAX_DEPTH = 500;

  private static final String INCERTAE_SEDIS = "incertae sedis";

  /** The values of one row that the tree needs, taken from its Lucene document. */
  record Row(
    String id,
    String parentId,
    String acceptedId,
    String rank,
    String status,
    String canonical,
    String formatted
  ) {}

  final int size;
  final String[] ids;
  final String[] ranks;
  final String[] statuses;
  final String[] canonicals;
  final String[] formatted;
  final int[] parent;
  final int[] accepted;
  private final Map<String, Integer> firstIndex;

  // children of each row as compressed sparse rows: children of i are childList[childStart[i]..childStart[i+1])
  private final int[] childStart;
  private final int[] childList;

  // nested set ranges, 0 when not set (ranges start at 1)
  private long[] lft;
  private long[] rgt;

  IndexTaxonomy(List<Row> rows) {
    size = rows.size();
    ids = new String[size];
    ranks = new String[size];
    statuses = new String[size];
    canonicals = new String[size];
    formatted = new String[size];
    parent = new int[size];
    accepted = new int[size];
    firstIndex = new HashMap<>(Math.max(16, (int) (size / 0.75f) + 1));

    // ranks and statuses come from small vocabularies, share one instance per value
    Map<String, String> vocab = new HashMap<>();
    for (int i = 0; i < size; i++) {
      Row r = rows.get(i);
      if (r == null) continue;
      ids[i] = r.id();
      ranks[i] = r.rank() == null ? null : vocab.computeIfAbsent(r.rank(), v -> v);
      statuses[i] = r.status() == null ? null : vocab.computeIfAbsent(r.status(), v -> v);
      canonicals[i] = r.canonical();
      formatted[i] = r.formatted();
      firstIndex.putIfAbsent(r.id(), i);
    }

    int[] childCount = new int[size];
    for (int i = 0; i < size; i++) {
      Row r = rows.get(i);
      if (r == null) {
        parent[i] = NONE;
        accepted[i] = NONE;
        continue;
      }
      parent[i] = resolve(r.parentId());
      accepted[i] = resolve(r.acceptedId());
      if (parent[i] >= 0) childCount[parent[i]]++;
    }

    childStart = new int[size + 1];
    for (int i = 0; i < size; i++) {
      childStart[i + 1] = childStart[i] + childCount[i];
    }
    childList = new int[childStart[size]];
    int[] fill = Arrays.copyOf(childStart, size);
    for (int i = 0; i < size; i++) {
      if (parent[i] >= 0) childList[fill[parent[i]]++] = i;
    }
  }

  private int resolve(String id) {
    if (id == null) return NONE;
    Integer idx = firstIndex.get(id);
    return idx == null ? UNRESOLVED : idx;
  }

  /** @return the row of the first occurrence of the id, or {@link #UNRESOLVED} */
  int indexOf(String id) {
    return id == null ? NONE : resolve(id);
  }

  /**
   * Mints nested set ranges by walking each accepted tree depth first, children ordered by canonical
   * name. Synonym roots get a single point; incertae sedis and non-accepted children are not visited.
   */
  void computeNestedSets() {
    lft = new long[size];
    rgt = new long[size];
    boolean[] visited = new boolean[size];
    long idx = 1;
    for (int root = 0; root < size; root++) {
      if (ids[root] != null && parent[root] == NONE && !INCERTAE_SEDIS.equalsIgnoreCase(canonicals[root])) {
        long left = idx;
        idx = nestedSet(root, idx, visited);
        log.info("[Nested set] Root taxon: {} left: {} right: {}", canonicals[root], left, idx);
      }
    }
  }

  /** A node on the depth first walk, with the position of its next child to visit. */
  private static final class Frame {
    final int node;
    final long left;
    final int[] children;
    int next;

    Frame(int node, long left, int[] children) {
      this.node = node;
      this.left = left;
      this.children = children;
    }
  }

  private long nestedSet(int root, long idx, boolean[] visited) {
    String status = statuses[root];
    if (status != null && !status.equalsIgnoreCase(TaxonomicStatus.ACCEPTED.name())) {
      idx++;
      lft[root] = idx;
      rgt[root] = idx;
      return idx;
    }

    // iterative so deep trees cannot overflow the stack; a node's left is the index when it is entered,
    // the index then advances by its number of children and its right is the index once all are done
    Deque<Frame> stack = new ArrayDeque<>();
    visited[root] = true;
    int[] kids = acceptedChildren(root);
    stack.push(new Frame(root, idx, kids));
    idx += kids.length;

    while (!stack.isEmpty()) {
      Frame f = stack.peek();
      if (f.next < f.children.length) {
        int child = f.children[f.next++];
        if (visited[child]) continue;
        visited[child] = true;
        int[] grandKids = acceptedChildren(child);
        stack.push(new Frame(child, idx, grandKids));
        idx += grandKids.length;
      } else {
        stack.pop();
        lft[f.node] = f.left;
        rgt[f.node] = idx;
      }
    }
    return idx;
  }

  /** Accepted children that are not incertae sedis, sorted by canonical name. */
  private int[] acceptedChildren(int node) {
    List<Integer> kids = new ArrayList<>();
    for (int c = childStart[node]; c < childStart[node + 1]; c++) {
      int child = childList[c];
      if (!INCERTAE_SEDIS.equalsIgnoreCase(canonicals[child])
        && TaxonomicStatus.ACCEPTED.name().equalsIgnoreCase(statuses[child])) {
        kids.add(child);
      }
    }
    kids.sort(Comparator.comparing(k -> canonicals[k]));
    return kids.stream().mapToInt(Integer::intValue).toArray();
  }

  /**
   * The nested set range a row is indexed with: its own for accepted (or status-less) usages, the
   * parent's (i.e. the accepted usage's) for everything else.
   *
   * @return {left, right} or null if there is none
   */
  long[] nestedSetFor(int i) {
    String status = statuses[i];
    int target;
    if (status != null && !status.equals(TaxonomicStatus.ACCEPTED.name())) {
      target = parent[i];
      if (target == NONE) {
        log.error("Taxon ID {} has status {}, with parentID {}", ids[i], status, null);
        return null;
      }
    } else {
      target = i;
    }
    if (target < 0 || lft[target] == 0) return null;
    return new long[] {lft[target], rgt[target]};
  }

  /**
   * The stored higher classification of a row: the chain from the root down to its parent, or to its
   * accepted usage for synonyms, plus the row itself when accepted.
   */
  StoredClassification classification(int i) {
    ArrayDeque<StoredName> names = new ArrayDeque<>();
    int n = accepted[i] != NONE ? accepted[i] : parent[i];
    int depth = 0;
    while (n >= 0) {
      if (++depth > MAX_DEPTH) {
        log.warn("Classification of {} deeper than {}, possible cycle", ids[i], MAX_DEPTH);
        break;
      }
      names.addFirst(StoredName.builder().key(ids[n]).rank(ranks[n]).name(canonicals[n]).build());
      n = parent[n];
    }

    String status = statuses[i];
    if (TaxonomicStatus.ACCEPTED.name().equalsIgnoreCase(status)
      || TaxonomicStatus.PROVISIONALLY_ACCEPTED.name().equalsIgnoreCase(status)) {
      names.addLast(StoredName.builder().key(ids[i]).rank(ranks[i]).name(canonicals[i]).build());
    }
    return StoredClassification.builder().names(new ArrayList<>(names)).build();
  }

  /**
   * Rank to canonical name for a row and all its ancestors, the higher ranks winning when a rank
   * occurs twice. Used to build the classification a source name is matched with.
   */
  Map<String, String> rankedHierarchy(int i) {
    Map<String, String> hierarchy = new HashMap<>();
    int n = indexOf(ids[i]);
    int depth = 0;
    while (n >= 0) {
      if (++depth > MAX_DEPTH) {
        log.warn("Hierarchy of {} deeper than {}, possible cycle", ids[i], MAX_DEPTH);
        break;
      }
      hierarchy.put(ranks[n], canonicals[n]);
      n = parent[n];
    }
    return hierarchy;
  }
}
