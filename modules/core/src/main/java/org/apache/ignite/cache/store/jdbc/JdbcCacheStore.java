/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.cache.store.jdbc;

import org.apache.ignite.*;
import org.apache.ignite.cache.query.*;
import org.apache.ignite.cache.store.*;
import org.apache.ignite.cache.store.jdbc.dialect.*;
import org.apache.ignite.internal.util.tostring.*;
import org.apache.ignite.internal.util.typedef.*;
import org.apache.ignite.internal.util.typedef.internal.*;
import org.apache.ignite.lang.*;
import org.apache.ignite.resources.*;
import org.apache.ignite.transactions.*;
import org.jetbrains.annotations.*;

import javax.cache.*;
import javax.cache.integration.*;
import javax.sql.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * Base {@link CacheStore} implementation backed by JDBC. This implementation stores objects in underlying database
 * using mapping description.
 * <p>
 * <h2 class="header">Configuration</h2>
 * Sections below describe mandatory and optional configuration settings as well
 * as providing example using Java and Spring XML.
 * <h3>Mandatory</h3>
 * There are no mandatory configuration parameters.
 * <h3>Optional</h3>
 * <ul>
 *     <li>Data source (see {@link #setDataSource(DataSource)}</li>
 *     <li>Connection URL (see {@link #setConnectionUrl(String)})</li>
 *     <li>User name (see {@link #setUser(String)})</li>
 *     <li>Password (see {@link #setPassword(String)})</li>
 *     <li>Create table query (see {@link #setConnectionUrl(String)})</li>
 *     <li>Maximum batch size for writeAll and deleteAll operations. (see {@link #setBatchSize(int)})</li>
 *     <li>Max workers thread count. These threads are responsible for load cache. (see {@link #setMaxPoolSize(int)})</li>
 *     <li>Parallel load cache minimum threshold. (see {@link #setParallelLoadCacheMinimumThreshold(int)})</li>
 * </ul>
 * <h2 class="header">Java Example</h2>
 * <pre name="code" class="java">
 *     ...
 *     JdbcPojoCacheStore store = new JdbcPojoCacheStore();
 *     ...
 *
 * </pre>
 * <h2 class="header">Spring Example</h2>
 * <pre name="code" class="xml">
 *     ...
 *     &lt;bean id=&quot;cache.jdbc.store&quot;
 *         class=&quot;org.apache.ignite.cache.store.jdbc.JdbcPojoCacheStore&quot;&gt;
 *         &lt;property name=&quot;connectionUrl&quot; value=&quot;jdbc:h2:mem:&quot;/&gt;
 *     &lt;/bean&gt;
 *     ...
 * </pre>
 * <p>
 * For information about Spring framework visit <a href="http://www.springframework.org/">www.springframework.org</a>
 */
public abstract class JdbcCacheStore<K, V> extends CacheStore<K, V> {
    /** Default batch size for put and remove operations. */
    protected static final int DFLT_BATCH_SIZE = 512;

    /** Default batch size for put and remove operations. */
    protected static final int DFLT_PARALLEL_LOAD_CACHE_MINIMUM_THRESHOLD = 512;

    /** Connection attribute property name. */
    protected static final String ATTR_CONN_PROP = "JDBC_STORE_CONNECTION";

    /** Auto-injected logger instance. */
    @IgniteLoggerResource
    protected IgniteLogger log;

    /** Init guard. */
    @GridToStringExclude
    private final AtomicBoolean initGuard = new AtomicBoolean();

    /** Init latch. */
    @GridToStringExclude
    private final CountDownLatch initLatch = new CountDownLatch(1);

    /** Init lock. */
    @GridToStringExclude
    private final Lock initLock = new ReentrantLock();

    /** Successful initialization flag. */
    private boolean initOk;

    /** Data source. */
    protected DataSource dataSrc;

    /** Connection URL. */
    protected String connUrl;

    /** User name for database access. */
    protected String user;

    /** Password for database access. */
    @GridToStringExclude
    protected String passwd;

    /** Cache with entry mapping description. (cache name, (key id, mapping description)). */
    protected Map<Integer, Map<Object, EntryMapping>> cacheMappings = new ConcurrentHashMap<>();

    /** Database dialect. */
    protected JdbcDialect dialect;

    /** Max workers thread count. These threads are responsible for load cache. */
    private int maxPoolSz = Runtime.getRuntime().availableProcessors();

    /** Maximum batch size for writeAll and deleteAll operations. */
    private int batchSz = DFLT_BATCH_SIZE;

    /** Parallel load cache minimum threshold. If {@code 0} then load sequentially. */
    private int parallelLoadCacheMinThreshold = DFLT_PARALLEL_LOAD_CACHE_MINIMUM_THRESHOLD;

    /**
     * Get field value from object.
     *
     * @param typeName Type name.
     * @param fieldName Field name.
     * @param obj Cache object.
     * @return Field value from object.
     */
    @Nullable protected abstract Object extractField(String typeName, String fieldName, Object obj)
        throws CacheException;

    /**
     * Construct object from query result.
     *
     * @param <R> Type of result object.
     * @param typeName Type name.
     * @param fields Fields descriptors.
     * @param rs ResultSet.
     * @return Constructed object.
     */
    protected abstract <R> R buildObject(String typeName, Collection<CacheQueryTypeDescriptor> fields, ResultSet rs)
        throws CacheLoaderException;

    /**
     * Extract type id from key object.
     *
     * @param key Key object.
     * @return Type id.
     */
    protected abstract Object keyId(Object key) throws CacheException;

    /**
     * Extract type id from key class name.
     *
     * @param type String description of key type.
     * @return Type id.
     */
    protected abstract Object keyId(String type) throws CacheException;

    /**
     * Build cache for mapped types.
     *
     * @throws CacheException If failed to initialize.
     */
    protected abstract void buildTypeCache(Collection<CacheQueryTypeMetadata> typeMetadata) throws CacheException;

    /**
     * Perform dialect resolution.
     *
     * @return The resolved dialect.
     * @throws CacheException Indicates problems accessing the metadata.
     */
    protected JdbcDialect resolveDialect() throws CacheException {
        Connection conn = null;

        String dbProductName = null;

        try {
            conn = openConnection(false);

            dbProductName = conn.getMetaData().getDatabaseProductName();
        }
        catch (SQLException e) {
            throw new CacheException("Failed access to metadata for detect database dialect.", e);
        }
        finally {
            U.closeQuiet(conn);
        }

        if ("H2".equals(dbProductName))
            return new H2Dialect();

        if ("MySQL".equals(dbProductName))
            return new MySQLDialect();

        if (dbProductName.startsWith("Microsoft SQL Server"))
            return new SQLServerDialect();

        if ("Oracle".equals(dbProductName))
            return new OracleDialect();

        if (dbProductName.startsWith("DB2/"))
            return new DB2Dialect();

        log.warning("Unknown database: " + dbProductName + ". BasicJdbcDialect will be used.");

        return new BasicJdbcDialect();
    }

    /**
     *
     * @return Cache key id.
     */
    protected Integer cacheKeyId() {
        String cacheName = session().cacheName();

        return cacheName != null ? cacheName.hashCode() : 0;
    }

    /**
     * Initializes store.
     *
     * @throws CacheException If failed to initialize.
     */
    private void init() throws CacheException {
        if (initLatch.getCount() > 0) {
            if (initGuard.compareAndSet(false, true)) {
                if (log.isDebugEnabled())
                    log.debug("Initializing cache store.");

                if (dataSrc == null && F.isEmpty(connUrl))
                    throw new CacheException("Failed to initialize cache store (connection is not provided).");

                try {
                    if (dialect == null)
                        dialect = resolveDialect();

                    initOk = true;
                }
                finally {
                    initLatch.countDown();
                }
            }
            else
                try {
                    if (initLatch.getCount() > 0)
                        initLatch.await();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    throw new CacheException(e);
                }
        }

        if (!initOk)
            throw new CacheException("Cache store was not properly initialized.");

        Integer cacheKey = cacheKeyId();

        if (!cacheMappings.containsKey(cacheKey)) {
            initLock.lock();

            try {
                if (!cacheMappings.containsKey(cacheKey)) {
                    Collection<CacheQueryTypeMetadata> typeMetadata =
                        ignite().cache(session().cacheName()).configuration().getQueryConfiguration().getTypeMetadata();

                    Map<Object, EntryMapping> entryMappings = U.newHashMap(typeMetadata.size());

                    for (CacheQueryTypeMetadata type : typeMetadata)
                        entryMappings.put(keyId(type.getKeyType()), new EntryMapping(dialect, type));

                    cacheMappings.put(cacheKey, Collections.unmodifiableMap(entryMappings));

                    buildTypeCache(typeMetadata);
                }
            }
            finally {
                initLock.unlock();
            }
        }
    }

    /**
     * Gets connection from a pool.
     *
     * @param autocommit {@code true} If connection should use autocommit mode.
     * @return Pooled connection.
     * @throws SQLException In case of error.
     */
    protected Connection openConnection(boolean autocommit) throws SQLException {
        Connection conn = dataSrc != null ? dataSrc.getConnection() :
            DriverManager.getConnection(connUrl, user, passwd);

        conn.setAutoCommit(autocommit);

        return conn;
    }

    /**
     * @return Connection.
     * @throws SQLException In case of error.
     */
    protected Connection connection() throws SQLException {
        CacheStoreSession ses = session();

        if (ses.transaction() != null) {
            Map<String, Connection> prop = ses.properties();

            Connection conn = prop.get(ATTR_CONN_PROP);

            if (conn == null) {
                conn = openConnection(false);

                // Store connection in session to used it for other operations in the same session.
                prop.put(ATTR_CONN_PROP, conn);
            }

            return conn;
        }
        // Transaction can be null in case of simple load operation.
        else
            return openConnection(true);
    }

    /**
     * Closes connection.
     *
     * @param conn Connection to close.
     */
    protected void closeConnection(@Nullable Connection conn) {
        CacheStoreSession ses = session();

        // Close connection right away if there is no transaction.
        if (ses.transaction() == null)
            U.closeQuiet(conn);
    }

    /**
     * Closes allocated resources depending on transaction status.
     *
     * @param conn Allocated connection.
     * @param st Created statement,
     */
    protected void end(@Nullable Connection conn, @Nullable Statement st) {
        U.closeQuiet(st);

        closeConnection(conn);
    }

    /** {@inheritDoc} */
    @Override public void txEnd(boolean commit) throws CacheWriterException {
        CacheStoreSession ses = session();

        IgniteTx tx = ses.transaction();

        Connection conn = ses.<String, Connection>properties().remove(ATTR_CONN_PROP);

        if (conn != null) {
            assert tx != null;

            try {
                if (commit)
                    conn.commit();
                else
                    conn.rollback();
            }
            catch (SQLException e) {
                throw new CacheWriterException(
                    "Failed to end transaction [xid=" + tx.xid() + ", commit=" + commit + ']', e);
            }
            finally {
                U.closeQuiet(conn);
            }
        }

        if (tx != null && log.isDebugEnabled())
            log.debug("Transaction ended [xid=" + tx.xid() + ", commit=" + commit + ']');
    }

    /**
     * Construct load cache from range.
     *
     * @param m Type mapping description.
     * @param clo Closure that will be applied to loaded values.
     * @param lowerBound Lower bound for range.
     * @param upperBound Upper bound for range.
     * @return Callable for pool submit.
     */
    private Callable<Void> loadCacheRange(final EntryMapping m, final IgniteBiInClosure<K, V> clo,
        @Nullable final Object[] lowerBound, @Nullable final Object[] upperBound) {
        return new Callable<Void>() {
            @Override public Void call() throws Exception {
                Connection conn = null;

                PreparedStatement stmt = null;

                try {
                    conn = openConnection(true);

                    stmt = conn.prepareStatement(lowerBound == null && upperBound == null
                        ? m.loadCacheQry
                        : m.loadCacheRangeQuery(lowerBound != null, upperBound != null));

                    int ix = 1;

                    if (lowerBound != null)
                        for (int i = lowerBound.length; i > 0; i--)
                            for (int j = 0; j < i; j++)
                                stmt.setObject(ix++, lowerBound[j]);

                    if (upperBound != null)
                        for (int i = upperBound.length; i > 0; i--)
                            for (int j = 0; j < i; j++)
                                stmt.setObject(ix++, upperBound[j]);

                    ResultSet rs = stmt.executeQuery();

                    while (rs.next()) {
                        K key = buildObject(m.keyType(), m.keyDescriptors(), rs);
                        V val = buildObject(m.valueType(), m.valueDescriptors(), rs);

                        clo.apply(key, val);
                    }
                }
                catch (SQLException e) {
                    throw new IgniteCheckedException("Failed to load cache", e);
                }
                finally {
                    U.closeQuiet(stmt);

                    U.closeQuiet(conn);
                }

                return null;
            }
        };
    }

    /**
     * Construct load cache in one select.
     *
     * @param m Type mapping description.
     * @param clo Closure for loaded values.
     * @return Callable for pool submit.
     */
    private Callable<Void> loadCacheFull(final EntryMapping m, final IgniteBiInClosure<K, V> clo) {
        return loadCacheRange(m, clo, null, null);
    }

    /**
     * @param key Entry key.
     * @return Entry mapping.
     * @throws CacheException if mapping for key was not found.
     */
    private EntryMapping entryMapping(Object keyId, Object key) throws CacheException {
        String cacheName = session().cacheName();

        init();

        EntryMapping em = cacheMappings.get(cacheKeyId()).get(keyId);

        if (em == null)
            throw new CacheException("Failed to find mapping description for key: " + key +
                " in cache: " + (cacheName != null ? cacheName : "<default>"));

        return em;
    }

    /** {@inheritDoc} */
    @Override public void loadCache(final IgniteBiInClosure<K, V> clo, @Nullable Object... args)
        throws CacheLoaderException {
        try {
            ExecutorService pool = Executors.newFixedThreadPool(maxPoolSz);

            Collection<Future<?>> futs = new ArrayList<>();

            if (args != null && args.length > 0) {
                if (args.length % 2 != 0)
                    throw new CacheLoaderException("Expected even number of arguments, but found: " + args.length);

                if (log.isDebugEnabled())
                    log.debug("Start loading entries from db using user queries from arguments");

                for (int i = 0; i < args.length; i += 2) {
                    String keyType = args[i].toString();

                    String selQry = args[i + 1].toString();

                    EntryMapping em = entryMapping(keyId(keyType), keyType);

                    futs.add(pool.submit(new LoadCacheCustomQueryWorker<>(em, selQry, clo)));
                }
            }
            else {
                init();

                if (log.isDebugEnabled())
                    log.debug("Start loading all cache types entries from db");

                for (EntryMapping em : cacheMappings.get(cacheKeyId()).values()) {
                    if (parallelLoadCacheMinThreshold > 0) {
                        Connection conn = null;

                        try {
                            conn = connection();

                            PreparedStatement stmt = conn.prepareStatement(em.loadCacheSelRangeQry);

                            stmt.setInt(1, parallelLoadCacheMinThreshold);

                            ResultSet rs = stmt.executeQuery();

                            if (rs.next()) {
                                int keyCnt = em.keyCols.size();

                                Object[] upperBound = new Object[keyCnt];

                                for (int i = 0; i < keyCnt; i++)
                                    upperBound[i] = rs.getObject(i + 1);

                                futs.add(pool.submit(loadCacheRange(em, clo, null, upperBound)));

                                while (rs.next()) {
                                    Object[] lowerBound = upperBound;

                                    upperBound = new Object[keyCnt];

                                    for (int i = 0; i < keyCnt; i++)
                                        upperBound[i] = rs.getObject(i + 1);

                                    futs.add(pool.submit(loadCacheRange(em, clo, lowerBound, upperBound)));
                                }

                                futs.add(pool.submit(loadCacheRange(em, clo, upperBound, null)));
                            }
                            else
                                futs.add(pool.submit(loadCacheFull(em, clo)));
                        }
                        catch (SQLException ignored) {
                            futs.add(pool.submit(loadCacheFull(em, clo)));
                        }
                        finally {
                            U.closeQuiet(conn);
                        }
                    }
                    else
                        futs.add(pool.submit(loadCacheFull(em, clo)));
                }
            }

            for (Future<?> fut : futs)
                U.get(fut);
        }
        catch (IgniteCheckedException e) {
            throw new CacheLoaderException("Failed to load cache", e.getCause());
        }
    }

    /** {@inheritDoc} */
    @Nullable @Override public V load(K key) throws CacheLoaderException {
        assert key != null;

        EntryMapping em = entryMapping(keyId(key), key);

        if (log.isDebugEnabled())
            log.debug("Start load value from database by key: " + key);

        Connection conn = null;

        PreparedStatement stmt = null;

        try {
            conn = connection();

            stmt = conn.prepareStatement(em.loadQrySingle);

            fillKeyParameters(stmt, em, key);

            ResultSet rs = stmt.executeQuery();

            if (rs.next())
                return buildObject(em.valueType(), em.valueDescriptors(), rs);
        }
        catch (SQLException e) {
            throw new CacheLoaderException("Failed to load object by key: " + key, e);
        }
        finally {
            end(conn, stmt);
        }

        return null;
    }

    /** {@inheritDoc} */
    @Override public Map<K, V> loadAll(Iterable<? extends K> keys) throws CacheLoaderException {
        assert keys != null;

        Connection conn = null;

        try {
            conn = connection();

            Map<Object, LoadWorker<K, V>> workers = U.newHashMap(cacheMappings.get(cacheKeyId()).size());

            Map<K, V> res = new HashMap<>();

            for (K key : keys) {
                Object keyId = keyId(key);

                EntryMapping em = entryMapping(keyId, key);

                LoadWorker<K, V> worker = workers.get(keyId);

                if (worker == null)
                    workers.put(keyId, worker = new LoadWorker<>(conn, em));

                worker.keys.add(key);

                if (worker.keys.size() == em.maxKeysPerStmt)
                    res.putAll(workers.remove(keyId).call());
            }

            for (LoadWorker<K, V> worker : workers.values())
                res.putAll(worker.call());

            return res;
        }
        catch (SQLException e) {
            throw new CacheWriterException("Failed to open connection", e);
        }
        catch (Exception e) {
            throw new CacheWriterException("Failed to load entries from database", e);
        }
        finally {
            closeConnection(conn);
        }
    }

    /** {@inheritDoc} */
    @Override public void write(Cache.Entry<? extends K, ? extends V> entry) throws CacheWriterException {
        assert entry != null;

        K key = entry.getKey();

        EntryMapping em = entryMapping(keyId(key), key);

        if (log.isDebugEnabled())
            log.debug("Start write entry to database: " + entry);

        Connection conn = null;

        PreparedStatement stmt = null;

        try {
            conn = connection();

            if (dialect.hasMerge()) {
                stmt = conn.prepareStatement(em.mergeQry);

                int i = fillKeyParameters(stmt, em, key);

                fillValueParameters(stmt, i, em, entry.getValue());

                stmt.executeUpdate();
            }
            else {
                V val = entry.getValue();

                stmt = conn.prepareStatement(em.updQry);

                int i = fillValueParameters(stmt, 1, em, val);

                fillKeyParameters(stmt, i, em, key);

                if (stmt.executeUpdate() == 0) {
                    stmt.close();

                    stmt = conn.prepareStatement(em.insQry);

                    i = fillKeyParameters(stmt, em, key);

                    fillValueParameters(stmt, i, em, val);

                    stmt.executeUpdate();
                }
            }
        }
        catch (SQLException e) {
            throw new CacheWriterException("Failed to write entry to database: " + entry, e);
        }
        finally {
            end(conn, stmt);
        }
    }

    /** {@inheritDoc} */
    @Override public void writeAll(Collection<Cache.Entry<? extends K, ? extends V>> entries)
        throws CacheWriterException {
        assert entries != null;

        Connection conn = null;

        try {
            conn = connection();

            if (dialect.hasMerge()) {
                Map<Object, PreparedStatement> stmts = U.newHashMap(cacheMappings.get(cacheKeyId()).size());

                Object prevKeyId = null;

                PreparedStatement mergeStmt = null;

                int cnt = 0;

                for (Cache.Entry<? extends K, ? extends V> entry : entries) {
                    K key = entry.getKey();

                    Object keyId = keyId(key);

                    EntryMapping em = entryMapping(keyId, key);

                    if (prevKeyId != null && !prevKeyId.equals(keyId)) {
                        mergeStmt = stmts.get(prevKeyId);

                        mergeStmt.executeBatch();

                        cnt = 0;
                    }

                    prevKeyId = keyId;

                    mergeStmt = stmts.get(keyId);

                    if (mergeStmt == null)
                        stmts.put(keyId, mergeStmt = conn.prepareStatement(em.mergeQry));

                    int i = fillKeyParameters(mergeStmt, em, key);

                    fillValueParameters(mergeStmt, i, em, entry.getValue());

                    mergeStmt.addBatch();

                    if (cnt++ % batchSz == 0)
                        mergeStmt.executeBatch();
                }

                if (mergeStmt != null && cnt % batchSz != 0)
                    mergeStmt.executeBatch();

                for (PreparedStatement st : stmts.values())
                    U.closeQuiet(st);
            }
            else {
                Map<Object, T2<PreparedStatement, PreparedStatement>> stmts =
                    U.newHashMap(cacheMappings.get(cacheKeyId()).size());

                for (Cache.Entry<? extends K, ? extends V> entry : entries) {
                    K key = entry.getKey();

                    Object keyId = keyId(key);

                    EntryMapping em = entryMapping(keyId, key);

                    T2<PreparedStatement, PreparedStatement> pair = stmts.get(keyId);

                    if (pair == null)
                        stmts.put(keyId,
                            pair = new T2<>(conn.prepareStatement(em.updQry), conn.prepareStatement(em.insQry)));

                    PreparedStatement updStmt = pair.get1();

                    assert updStmt != null;

                    int i = fillValueParameters(updStmt, 1, em, entry.getValue());

                    fillKeyParameters(updStmt, i, em, key);

                    if (updStmt.executeUpdate() == 0) {
                        PreparedStatement insStmt = pair.get2();

                        assert insStmt != null;

                        i = fillKeyParameters(insStmt, em, key);

                        fillValueParameters(insStmt, i, em, entry.getValue());

                        insStmt.executeUpdate();
                    }
                }

                for (T2<PreparedStatement, PreparedStatement> pair :  stmts.values()) {
                    U.closeQuiet(pair.get1());

                    U.closeQuiet(pair.get2());
                }
            }
        }
        catch (SQLException e) {
            throw new CacheWriterException("Failed to open connection", e);
        }
        finally {
            closeConnection(conn);
        }
    }

    /** {@inheritDoc} */
    @Override public void delete(Object key) throws CacheWriterException {
        assert key != null;

        EntryMapping em = entryMapping(keyId(key), key);

        if (log.isDebugEnabled())
            log.debug("Start remove value from database by key: " + key);

        Connection conn = null;

        PreparedStatement stmt = null;

        try {
            conn = connection();

            stmt = conn.prepareStatement(em.remQry);

            fillKeyParameters(stmt, em, key);

            stmt.executeUpdate();
        }
        catch (SQLException e) {
            throw new CacheWriterException("Failed to remove value from database by key: " + key, e);
        }
        finally {
            end(conn, stmt);
        }
    }

    /** {@inheritDoc} */
    @Override public void deleteAll(Collection<?> keys) throws CacheWriterException {
        assert keys != null;

        Connection conn = null;

        try {
            conn = connection();

            Map<Object, PreparedStatement> stmts = U.newHashMap(cacheMappings.get(cacheKeyId()).size());

            Object prevKeyId = null;

            PreparedStatement delStmt = null;

            int cnt = 0;

            for (Object key : keys) {
                Object keyId = keyId(key);

                EntryMapping em = entryMapping(keyId, key);

                if (prevKeyId != null && !prevKeyId.equals(keyId)) {
                    delStmt = stmts.get(prevKeyId);

                    delStmt.executeBatch();

                    cnt = 0;
                }

                prevKeyId = keyId;

                delStmt = stmts.get(keyId);

                if (delStmt == null)
                    stmts.put(keyId, delStmt = conn.prepareStatement(em.remQry));

                fillKeyParameters(delStmt, em, key);

                delStmt.addBatch();

                if (cnt++ % batchSz == 0)
                    delStmt.executeBatch();
            }

            if (delStmt != null && cnt % batchSz != 0)
                delStmt.executeBatch();

            for (PreparedStatement st : stmts.values())
                U.closeQuiet(st);
        }
        catch (SQLException e) {
            throw new CacheWriterException("Failed to open connection", e);
        }
        catch (Exception e) {
            throw new CacheWriterException("Failed to remove values from database", e);
        }
        finally {
            closeConnection(conn);
        }
    }

    /**
     * @param stmt Prepare statement.
     * @param i Start index for parameters.
     * @param type Type description.
     * @param key Key object.
     * @return Next index for parameters.
     */
    protected int fillKeyParameters(PreparedStatement stmt, int i, EntryMapping type,
        Object key) throws CacheException {
        for (CacheQueryTypeDescriptor field : type.keyDescriptors()) {
            Object fieldVal = extractField(type.keyType(), field.getJavaName(), key);

            try {
                if (fieldVal != null)
                    stmt.setObject(i++, fieldVal);
                else
                    stmt.setNull(i++, field.getDbType());
            }
            catch (SQLException e) {
                throw new CacheException("Failed to set statement parameter name: " + field.getDbName(), e);
            }
        }

        return i;
    }

    /**
     * @param stmt Prepare statement.
     * @param m Type mapping description.
     * @param key Key object.
     * @return Next index for parameters.
     */
    protected int fillKeyParameters(PreparedStatement stmt, EntryMapping m, Object key) throws CacheException {
        return fillKeyParameters(stmt, 1, m, key);
    }

    /**
     * @param stmt Prepare statement.
     * @param i Start index for parameters.
     * @param m Type mapping description.
     * @param val Value object.
     * @return Next index for parameters.
     */
    protected int fillValueParameters(PreparedStatement stmt, int i, EntryMapping m, Object val)
        throws CacheWriterException {
        for (CacheQueryTypeDescriptor field : m.uniqValFields) {
            Object fieldVal = extractField(m.valueType(), field.getJavaName(), val);

            try {
                if (fieldVal != null)
                    stmt.setObject(i++, fieldVal);
                else
                    stmt.setNull(i++, field.getDbType());
            }
            catch (SQLException e) {
                throw new CacheWriterException("Failed to set statement parameter name: " + field.getDbName(), e);
            }
        }

        return i;
    }

    /**
     * @return Data source.
     */
    public DataSource getDataSource() {
        return dataSrc;
    }

    /**
     * @param dataSrc Data source.
     */
    public void setDataSource(DataSource dataSrc) {
        this.dataSrc = dataSrc;
    }

    /**
     * @return Connection URL.
     */
    public String getConnectionUrl() {
        return connUrl;
    }

    /**
     * @param connUrl Connection URL.
     */
    public void setConnectionUrl(String connUrl) {
        this.connUrl = connUrl;
    }

    /**
     * @return Password for database access.
     */
    public String getPassword() {
        return passwd;
    }

    /**
     * @param passwd Password for database access.
     */
    public void setPassword(String passwd) {
        this.passwd = passwd;
    }

    /**
     * @return User name for database access.
     */
    public String getUser() {
        return user;
    }

    /**
     * @param user User name for database access.
     */
    public void setUser(String user) {
        this.user = user;
    }

    /**
     * Get database dialect.
     *
     * @return Database dialect.
     */
    public JdbcDialect getDialect() {
        return dialect;
    }

    /**
     * Set database dialect.
     *
     * @param dialect Database dialect.
     */
    public void setDialect(JdbcDialect dialect) {
        this.dialect = dialect;
    }

    /**
     * Get Max workers thread count. These threads are responsible for execute query.
     *
     * @return Max workers thread count.
     */
    public int getMaxPoolSize() {
        return maxPoolSz;
    }

    /**
     * Set Max workers thread count. These threads are responsible for execute query.
     *
     * @param maxPoolSz Max workers thread count.
     */
    public void setMaxPoolSize(int maxPoolSz) {
        this.maxPoolSz = maxPoolSz;
    }

    /**
     * Get maximum batch size for delete and delete operations.
     *
     * @return Maximum batch size.
     */
    public int getBatchSize() {
        return batchSz;
    }

    /**
     * Set maximum batch size for write and delete operations.
     *
     * @param batchSz Maximum batch size.
     */
    public void setBatchSize(int batchSz) {
        this.batchSz = batchSz;
    }

    /**
     * Parallel load cache minimum row count threshold.
     *
     * @return If {@code 0} then load sequentially.
     */
    public int getParallelLoadCacheMinimumThreshold() {
        return parallelLoadCacheMinThreshold;
    }

    /**
     * Parallel load cache minimum row count threshold.
     *
     * @param parallelLoadCacheMinThreshold Minimum row count threshold. If {@code 0} then load sequentially.
     */
    public void setParallelLoadCacheMinimumThreshold(int parallelLoadCacheMinThreshold) {
        this.parallelLoadCacheMinThreshold = parallelLoadCacheMinThreshold;
    }

    /**
     * Entry mapping description.
     */
    protected static class EntryMapping {
        /** Database dialect. */
        private final JdbcDialect dialect;

        /** Select border for range queries. */
        protected final String loadCacheSelRangeQry;

        /** Select all items query. */
        protected final String loadCacheQry;

        /** Select item query. */
        protected final String loadQrySingle;

        /** Select items query. */
        private final String loadQry;

        /** Merge item(s) query. */
        protected final String mergeQry;

        /** Update item query. */
        protected final String insQry;

        /** Update item query. */
        protected final String updQry;

        /** Remove item(s) query. */
        protected final String remQry;

        /** Max key count for load query per statement. */
        protected final int maxKeysPerStmt;

        /** Database key columns. */
        private final Collection<String> keyCols;

        /** Database unique value columns. */
        private final Collection<String> cols;

        /** Unique value fields. */
        private final Collection<CacheQueryTypeDescriptor> uniqValFields;

        /** Type metadata. */
        private final CacheQueryTypeMetadata typeMetadata;

        /**
         * @param typeMetadata Type metadata.
         */
        public EntryMapping(JdbcDialect dialect, CacheQueryTypeMetadata typeMetadata) {
            this.dialect = dialect;

            this.typeMetadata = typeMetadata;

            final Collection<CacheQueryTypeDescriptor> keyFields = typeMetadata.getKeyDescriptors();

            Collection<CacheQueryTypeDescriptor> valFields = typeMetadata.getValueDescriptors();

            uniqValFields = F.view(typeMetadata.getValueDescriptors(),
                new IgnitePredicate<CacheQueryTypeDescriptor>() {
                    @Override public boolean apply(CacheQueryTypeDescriptor desc) {
                        return !keyFields.contains(desc);
                    }
                });

            String schema = typeMetadata.getSchema();

            String tblName = typeMetadata.getTableName();

            keyCols = databaseColumns(keyFields);

            Collection<String> valCols = databaseColumns(valFields);

            Collection<String> uniqValCols = databaseColumns(uniqValFields);

            cols = F.concat(false, keyCols, uniqValCols);

            loadCacheQry = dialect.loadCacheQuery(schema, tblName, cols);

            loadCacheSelRangeQry = dialect.loadCacheSelectRangeQuery(schema, tblName, keyCols);

            loadQrySingle = dialect.loadQuery(schema, tblName, keyCols, valCols, 1);

            maxKeysPerStmt = dialect.getMaxParamsCnt() / keyCols.size();

            loadQry = dialect.loadQuery(schema, tblName, keyCols, uniqValCols, maxKeysPerStmt);

            insQry = dialect.insertQuery(schema, tblName, keyCols, uniqValCols);

            updQry = dialect.updateQuery(schema, tblName, keyCols, uniqValCols);

            mergeQry = dialect.mergeQuery(schema, tblName, keyCols, uniqValCols);

            remQry = dialect.removeQuery(schema, tblName, keyCols);
        }

        /**
         * Extract database column names from {@link CacheQueryTypeDescriptor}.
         *
         * @param dsc collection of {@link CacheQueryTypeDescriptor}.
         */
        private static Collection<String> databaseColumns(Collection<CacheQueryTypeDescriptor> dsc) {
            return F.transform(dsc, new C1<CacheQueryTypeDescriptor, String>() {
                /** {@inheritDoc} */
                @Override public String apply(CacheQueryTypeDescriptor desc) {
                    return desc.getDbName();
                }
            });
        }

        /**
         * Construct query for select values with key count less or equal {@code maxKeysPerStmt}
         *
         * @param keyCnt Key count.
         */
        protected String loadQuery(int keyCnt) {
            assert keyCnt <= maxKeysPerStmt;

            if (keyCnt == maxKeysPerStmt)
                return loadQry;

            if (keyCnt == 1)
                return loadQrySingle;

            return dialect.loadQuery(typeMetadata.getSchema(), typeMetadata.getTableName(), keyCols, cols, keyCnt);
        }
        /**
         * Construct query for select values in range.
         *
         * @param appendLowerBound Need add lower bound for range.
         * @param appendUpperBound Need add upper bound for range.
         * @return Query with range.
         */
        protected String loadCacheRangeQuery(boolean appendLowerBound, boolean appendUpperBound) {
            return dialect.loadCacheRangeQuery(typeMetadata.getSchema(), typeMetadata.getTableName(), keyCols, cols,
                appendLowerBound, appendUpperBound);
        }

        /** Key type. */
        protected String keyType() {
            return typeMetadata.getKeyType();
        }

        /** Value type. */
        protected String valueType() {
            return typeMetadata.getType();
        }

        /**
         * Gets key fields type descriptors.
         *
         * @return Key fields type descriptors.
         */
        protected Collection<CacheQueryTypeDescriptor> keyDescriptors() {
            return typeMetadata.getKeyDescriptors();
        }

        /**
         * Gets value fields type descriptors.
         *
         * @return Key value type descriptors.
         */
        protected Collection<CacheQueryTypeDescriptor> valueDescriptors() {
            return typeMetadata.getValueDescriptors();
        }
    }

    /**
     * Worker for load cache using custom user query.
     *
     * @param <K1> Key type.
     * @param <V1> Value type.
     */
    private class LoadCacheCustomQueryWorker<K1, V1> implements Callable<Void> {
        /** Entry mapping description. */
        private final EntryMapping m;

        /** User query. */
        private final String qry;

        /** Closure for loaded values. */
        private final IgniteBiInClosure<K1, V1> clo;

        /**
         * @param m Entry mapping description.
         * @param qry User query.
         * @param clo Closure for loaded values.
         */
        private LoadCacheCustomQueryWorker(EntryMapping m, String qry, IgniteBiInClosure<K1, V1> clo) {
            this.m = m;
            this.qry = qry;
            this.clo = clo;
        }

        /** {@inheritDoc} */
        @Override public Void call() throws Exception {
            Connection conn = null;

            PreparedStatement stmt = null;

            try {
                conn = openConnection(true);

                stmt = conn.prepareStatement(qry);

                ResultSet rs = stmt.executeQuery();

                while (rs.next()) {
                    K1 key = buildObject(m.keyType(), m.keyDescriptors(), rs);
                    V1 val = buildObject(m.valueType(), m.valueDescriptors(), rs);

                    clo.apply(key, val);
                }

                return null;
            } catch (SQLException e) {
                throw new CacheLoaderException("Failed to execute custom query for load cache", e);
            }
            finally {
                U.closeQuiet(stmt);

                U.closeQuiet(conn);
            }
        }
    }

    /**
     * Worker for load by keys.
     *
     * @param <K1> Key type.
     * @param <V1> Value type.
     */
    private class LoadWorker<K1, V1> implements Callable<Map<K1, V1>> {
        /** Connection. */
        private final Connection conn;

        /** Keys for load. */
        private final Collection<K1> keys;

        /** Entry mapping description. */
        private final EntryMapping m;

        /**
         * @param conn Connection.
         * @param m Entry mapping description.
         */
        private LoadWorker(Connection conn, EntryMapping m) {
            this.conn = conn;
            this.m = m;

            keys = new ArrayList<>(m.maxKeysPerStmt);
        }

        /** {@inheritDoc} */
        @Override public Map<K1, V1> call() throws Exception {
            PreparedStatement stmt = null;

            try {
                stmt = conn.prepareStatement(m.loadQuery(keys.size()));

                int i = 1;

                for (Object key : keys)
                    for (CacheQueryTypeDescriptor field : m.keyDescriptors()) {
                        Object fieldVal = extractField(m.keyType(), field.getJavaName(), key);

                        if (fieldVal != null)
                            stmt.setObject(i++, fieldVal);
                        else
                            stmt.setNull(i++, field.getDbType());
                    }

                ResultSet rs = stmt.executeQuery();

                Map<K1, V1> entries = U.newHashMap(keys.size());

                while (rs.next()) {
                    K1 key = buildObject(m.keyType(), m.keyDescriptors(), rs);
                    V1 val = buildObject(m.valueType(), m.valueDescriptors(), rs);

                    entries.put(key, val);
                }

                return entries;
            }
            finally {
                U.closeQuiet(stmt);
            }
        }
    }
}
