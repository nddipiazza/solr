package org.apache.solr.search;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.apache.lucene.util.FixedBitSet;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.impl.HttpSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrInputField;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Uses a sidecar solr collection to cache user group associations.
 * <br><br>
 * Cache table definition:
 * <br><br>
 * <pre>
 * CREATE TABLE `cache` (
 *   `id` bigint NOT NULL,
 *   `docset` mediumblob NOT NULL,
 *   `inserted_on` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
 *   PRIMARY KEY (`id`)
 * )
 * </pre>
 *
 */
public class SidecarDocSetCache {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final long CACHE_EXPIRY = Long.parseLong(System.getProperty("solr.doc-cache.expiry", "86400")); // 1 day
  public static final String
      SIDECAR_COLLECTION_NAME = System.getProperty("solr.doc-cache.sidecar.collection", "doc_cache");
  public static final String COUNT_I = "count_i";
  public static final String NUM_BITS_I = "num_bits_i";
  public static final String DOCSET_LS = "docset_ls";
  public static final String INSERTED_ON_L = "inserted_on_l";

  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong cacheMisses = new AtomicLong();
  private final AtomicLong cachePuts = new AtomicLong();
  private final AtomicLong cacheEvicts = new AtomicLong();
  private final Cache<Long, DocSet> ramCache = CacheBuilder.newBuilder()
      .maximumSize(256)
      .build();
  private final SolrClient solrClient;

  private static final Object instanceLock = new Object();
  private static volatile SidecarDocSetCache instance;

  public static SidecarDocSetCache getInstance() {
    SidecarDocSetCache r = instance;
    if (r == null) {
      synchronized (instanceLock) {
        r = instance;
        if (r == null) {
          r = new SidecarDocSetCache();
          instance = r;
        }
      }
    }
    return r;
  }

  private SidecarDocSetCache() {
    solrClient = new HttpSolrClient.Builder("http://127.0.0.1:8983/solr")
        .build();
  }

  public static Optional<DocSet> get(String q) {
    SidecarDocSetCache databaseDocSetCache = getInstance();
    return databaseDocSetCache.getDocSet(q);
  }

  private Optional<DocSet> getDocSet(String q) {
    long qUuid5 = UUID5Generator.toLongUUID5(q);
    DocSet cached = ramCache.getIfPresent(qUuid5);
    if (cached != null) {
      return Optional.of(cached);
    }
    Optional<DocSet> res = Optional.empty();
    boolean hit = false;

    SolrQuery sidecarQuery = new SolrQuery("id:" + qUuid5);
    sidecarQuery.setRows(1);
    sidecarQuery.setFilterQueries(String.format("%s:[%d TO *]", INSERTED_ON_L, System.currentTimeMillis() - CACHE_EXPIRY));
    sidecarQuery.setFields(DOCSET_LS, COUNT_I, NUM_BITS_I);

    try {
      QueryResponse qr = solrClient.query(SIDECAR_COLLECTION_NAME, sidecarQuery);
      if (!qr.getResults().isEmpty()) {
        cacheHits.incrementAndGet();
        SolrDocument resultSolrDoc = qr.getResults().get(0);
        Collection<Object> docsetCollection = resultSolrDoc.getFieldValues(DOCSET_LS);
        int idx = 0;
        long[] docs = new long[docsetCollection.size()];
        for (Object doc : docsetCollection) {
          docs[idx] = (Long) doc;
        }
        BitDocSet bitDocSet = new BitDocSet(new FixedBitSet(docs, (Integer) resultSolrDoc.getFirstValue(NUM_BITS_I)),
            (Integer) resultSolrDoc.getFirstValue(COUNT_I));
        ramCache.put(qUuid5, bitDocSet);
        res = Optional.of(bitDocSet);
        hit = true;
      }
    } catch (Exception ex) {
      log.error("Could not query solr " + sidecarQuery, ex);
    }
    log.info(
        "filter cache read: q={}, hit={}, cacheTotalHits={}, cacheTotalMisses={}, cacheTotalPuts={}, cacheTotalEvicts={}",
        q,
        hit,
        cacheHits.get(),
        cacheMisses.get(),
        cachePuts.get(),
        cacheEvicts.get());
    return res;
  }

  public static void put(String q, DocSet docSet) {
    SidecarDocSetCache databaseDocSetCache = getInstance();
    databaseDocSetCache.putDocSetInCache(q, docSet);
  }

  private void putDocSetInCache(String q, DocSet docSet) {
    if (docSet instanceof BitDocSet) {
      BitDocSet bitDocSet = (BitDocSet) docSet;
      SolrInputDocument solrInputDocument = new SolrInputDocument();
      solrInputDocument.setField("id", String.valueOf(UUID5Generator.toLongUUID5(q)));
      solrInputDocument.setField(INSERTED_ON_L, System.currentTimeMillis());
      solrInputDocument.setField(COUNT_I, bitDocSet.size);
      solrInputDocument.setField(NUM_BITS_I, bitDocSet.getBits().length());
      SolrInputField sif = new SolrInputField(DOCSET_LS);
      for (long i : bitDocSet.getFixedBitSet().getBits()) {
        sif.addValue(i);
      }
      solrInputDocument.put(DOCSET_LS, sif);
      try {
        solrClient.add(SIDECAR_COLLECTION_NAME, solrInputDocument, 5000);
        cachePuts.incrementAndGet();
      } catch (Exception ex) {
        log.error("Could not insert " + q + " into cache", ex);
      }
    }
  }
}
