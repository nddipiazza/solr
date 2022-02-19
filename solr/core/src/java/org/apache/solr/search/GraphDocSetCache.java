package org.apache.solr.search;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.util.FixedBitSet;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serializable on-disk cache.
 */
public class GraphDocSetCache implements Serializable {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String SOLR_GRAPH_CACHE_PATH =
      System.getProperty("solr.graph.cache.path", new File(System.getProperty("java.io.tmpdir"), "graph-cache.mapdb").getAbsolutePath());
  public static final long
      CACHE_EXPIRY_MILLIS = Long.parseLong(System.getProperty("solr.graph.cache.expiry", "86400000"));
  public static final String CACHE_TABLE_NAME = "doc_set_cache";
  private final BTreeMap<String, long[]> onDiskCache;
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong cacheMisses = new AtomicLong();
  private final AtomicLong cachePuts = new AtomicLong();
  private final AtomicLong cacheEvicts = new AtomicLong();
  private static final Object instanceLock = new Object();
  private static volatile GraphDocSetCache instance;

  public static GraphDocSetCache getInstance() {
    GraphDocSetCache r = instance;
    if (r == null) {
      synchronized (instanceLock) {
        r = instance;
        if (r == null) {
          r = new GraphDocSetCache();
          instance = r;
        }
      }
    }
    return r;
  }

  private GraphDocSetCache() {
    File graphCacheFile = new File(SOLR_GRAPH_CACHE_PATH);
    if (graphCacheFile.exists()) {
      FileUtils.deleteQuietly(graphCacheFile);
    }
    DBMaker.Maker maker = DBMaker
        .fileDB(graphCacheFile);
    DB db = maker.closeOnJvmShutdown()
        .make();
    onDiskCache = db.treeMap(CACHE_TABLE_NAME, Serializer.STRING, Serializer.LONG_ARRAY)
        .createOrOpen();
  }

  public static long[] docSetToLongArray(BitDocSet bitDocSet) {
    long [] ar = new long[bitDocSet.getBits().getBits().length + 3];
    System.arraycopy(bitDocSet.getBits().getBits(), 0, ar, 0, bitDocSet.getBits().getBits().length);
    ar[bitDocSet.getBits().getBits().length] = bitDocSet.size(); // size
    ar[bitDocSet.getBits().getBits().length + 1] = bitDocSet.getBits().length(); // num bits
    ar[bitDocSet.getBits().getBits().length + 2] = System.currentTimeMillis();
    return ar;
  }

  public static Optional<DocSet> docSetFromLongArray(long [] longArray) {
    // n is length of bitset
    // 0, ..., n-4 = contain the bitset (long integer array)
    // n-3 = size
    // n-2 = numBits
    // n-1 = time in millis cache entry was added
    long [] bits = new long[longArray.length - 3];
    System.arraycopy(longArray, 0, bits, 0, bits.length);
    if (System.currentTimeMillis() - longArray[longArray.length - 1] > CACHE_EXPIRY_MILLIS) {
      getInstance().cacheEvicts.incrementAndGet();
      return Optional.empty();
    }
    return Optional.of(new BitDocSet(new FixedBitSet(bits, (int)longArray[longArray.length - 3]), (int)longArray[longArray.length - 2]));
  }

  public static Optional<DocSet> getGraphDocSetFromCache(String q) {
    GraphDocSetCache graphDocSetCache = getInstance();
    BTreeMap<String, long[]> graphCacheDocSet = graphDocSetCache.onDiskCache;
    boolean hit = graphCacheDocSet.containsKey(q);
    log.info("Graph on-disk filter cache read: q={}, hit={}, cacheTotalHits={}, cacheTotalMisses={}, cacheTotalPuts={}, cacheTotalEvicts={}",
        q,
        hit,
        graphDocSetCache.cacheHits.get(),
        graphDocSetCache.cacheMisses.get(),
        graphDocSetCache.cachePuts.get(),
        graphDocSetCache.cacheEvicts.get());
    if (hit) {
      graphDocSetCache.cacheHits.incrementAndGet();
      return docSetFromLongArray(Objects.requireNonNull(graphCacheDocSet.get(q)));
    }
    graphDocSetCache.cacheMisses.incrementAndGet();
    return Optional.empty();
  }

  public static void putGraphDocSetInCache(String q, DocSet docSet) {
    if (docSet instanceof BitDocSet) {
      GraphDocSetCache graphDocSetCache = getInstance();
      graphDocSetCache.onDiskCache.put(q, docSetToLongArray((BitDocSet) docSet));
      graphDocSetCache.cachePuts.incrementAndGet();
    }
  }
}
