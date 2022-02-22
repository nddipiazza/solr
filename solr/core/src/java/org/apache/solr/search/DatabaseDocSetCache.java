package org.apache.solr.search;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.lucene.util.FixedBitSet;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serializable on-disk cache.
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
public class DatabaseDocSetCache {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private final HikariDataSource ds;

  public static final long
      CACHE_EXPIRY = Long.parseLong(System.getProperty("solr.doc-cache.expiry", "86400")); // 1 day

  public static final String CACHE_TABLE_NAME = "cache";
  private final AtomicLong cacheHits = new AtomicLong();
  private final AtomicLong cacheMisses = new AtomicLong();
  private final AtomicLong cachePuts = new AtomicLong();
  private final AtomicLong cacheEvicts = new AtomicLong();
  private final Cache<Long, DocSet> ramCache = CacheBuilder.newBuilder()
      .maximumSize(256)
      .build();
  
  private static final Object instanceLock = new Object();
  private static volatile DatabaseDocSetCache instance;

  public static DatabaseDocSetCache getInstance() {
    DatabaseDocSetCache r = instance;
    if (r == null) {
      synchronized (instanceLock) {
        r = instance;
        if (r == null) {
          r = new DatabaseDocSetCache();
          instance = r;
        }
      }
    }
    return r;
  }

  private DatabaseDocSetCache() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(System.getProperty("solr.cache.db.jdbc-url",
        "jdbc:mysql://127.0.0.1:3306/cache?allowPublicKeyRetrieval=true&useSSL=false"));
    config.setUsername(System.getProperty("solr.cache.db.jdbc-username", "root"));
    config.setPassword(System.getProperty("solr.cache.db.jdbc-password", "pre374da"));
    config.addDataSourceProperty("cachePrepStmts", "true");
    config.addDataSourceProperty("prepStmtCacheSize", "250");
    config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
    ds = new HikariDataSource(config);
  }

  static class DatabaseDocSetCacheEntry implements Serializable {
    long[] docset;
    int count;
    int numBits;

    public DatabaseDocSetCacheEntry(long[] docset, int count, int numBits) {
      this.docset = docset;
      this.count = count;
      this.numBits = numBits;
    }
  }

  public static Optional<DocSet> get(String q) {
    DatabaseDocSetCache databaseDocSetCache = getInstance();
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

    try (Connection connection = ds.getConnection();
         PreparedStatement ps = connection.prepareStatement("SELECT docset FROM " + CACHE_TABLE_NAME + " WHERE id = ? AND TIMESTAMPDIFF(SECOND, inserted_on, CURRENT_TIMESTAMP()) < ?")) {
      ps.setLong(1, qUuid5);
      ps.setLong(2, CACHE_EXPIRY);
      ResultSet rs = ps.executeQuery();
      if (rs.next()) {
        try (InputStream docSetInputStream = rs.getBinaryStream(1)) {
          ObjectInputStream objectInputStream = new ObjectInputStream(docSetInputStream);
          DatabaseDocSetCacheEntry cacheEntry = (DatabaseDocSetCacheEntry) objectInputStream.readObject();
          cacheHits.incrementAndGet();
          BitDocSet bitDocSet = new BitDocSet(new FixedBitSet(cacheEntry.docset, cacheEntry.numBits), cacheEntry.count);
          ramCache.put(qUuid5, bitDocSet);
          res = Optional.of(bitDocSet);
          hit = true;
        }
      }
    } catch (ClassNotFoundException | SQLException | IOException ex) {
      log.error("Could not get " + q + " from cache", ex);
    }
    log.info(
        "Database docset cache read: q={}, hit={}, cacheTotalHits={}, cacheTotalMisses={}, cacheTotalPuts={}, cacheTotalEvicts={}",
        q,
        hit,
        cacheHits.get(),
        cacheMisses.get(),
        cachePuts.get(),
        cacheEvicts.get());
    return res;
  }

  public static void put(String q, DocSet docSet) {
    DatabaseDocSetCache databaseDocSetCache = getInstance();
    databaseDocSetCache.putDocSetInCache(q, docSet);
  }

  private void putDocSetInCache(String q, DocSet docSet) {
    if (docSet instanceof BitDocSet) {
      BitDocSet bitDocSet = (BitDocSet) docSet;
      try (Connection connection = ds.getConnection();
           PreparedStatement ps = connection.prepareStatement("REPLACE INTO " + CACHE_TABLE_NAME + " (id, docset) VALUES (?, ?)")) {
        ps.setLong(1, UUID5Generator.toLongUUID5(q));
        ps.setObject(2, new DatabaseDocSetCacheEntry(bitDocSet.getFixedBitSet().getBits(), bitDocSet.size, bitDocSet.getBits().length()));
        ps.executeUpdate();
      } catch (SQLException ex) {
        log.error("Could not insert " + q + " into cache", ex);
      }
      cachePuts.incrementAndGet();
    }
  }
}
