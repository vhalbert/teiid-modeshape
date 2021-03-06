/*
 * ModeShape (http://www.modeshape.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.modeshape.jcr.value.binary;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.modeshape.common.database.DatabaseType;
import org.modeshape.common.database.DatabaseUtil;
import org.modeshape.common.logging.Logger;
import org.modeshape.common.util.StringUtil;
import org.modeshape.jcr.JcrI18n;
import org.modeshape.jcr.value.BinaryKey;

/**
 * Helper class for manipulation with database.
 * <p>
 * This class looks for database SQL statements in properties files named "<code>binary_store_{type}_database.properties</code>"
 * located within the "org/modeshape/jcr/database" area of the classpath, where "<code>{type}</code>" is
 * {@link DatabaseUtil#determineType(java.sql.DatabaseMetaData) determined} from the connection, and matches one of the following:
 * <ul>
 * <li><code>mysql</code></li>
 * <li><code>postgres</code></li>
 * <li><code>derby</code></li>
 * <li><code>hsql</code></li>
 * <li><code>h2</code></li>
 * <li><code>sqlite</code></li>
 * <li><code>db2</code></li>
 * <li><code>db2_390</code></li>
 * <li><code>informix</code></li>
 * <li><code>interbase</code></li>
 * <li><code>firebird</code></li>
 * <li><code>sqlserver</code></li>
 * <li><code>access</code></li>
 * <li><code>oracle</code></li>
 * <li><code>sybase</code></li>
 * </ul>
 * If the corresponding file is not found on the classpath, then the "<code>binary_store_default_database.properties</code>" file
 * is used.
 * </p>
 * <p>
 * Each property file should contain the set of DDL and DML statements that are used by the binary store, and the
 * database-specific file allows database-specific schemas and queries to be used. If the properties file that corresponds to the
 * connection's database type is not found on the classpath, then the "<code>binary_store_default_database.properties</code>" file
 * is used.
 * </p>
 * <p>
 * ModeShape does not provide out-of-the-box properties files for each of the database types listed above. If you run into any
 * problems, you can override the statements by providing a property file that matches the naming pattern described above, and by
 * putting that file on the classpath. (If you want to override one of ModeShape's out-of-the-box properties files, then be sure
 * to put your custom file first on the classpath.)
 * </p>
 * 
 * @author kulikov
 * @author Horia Chiorean (hchiorea@redhat.com)
 */
public class Database {
    public static final String TABLE_NAME = "CONTENT_STORE";
    public static final String STATEMENTS_FILE_PATH = "org/modeshape/jcr/database/";

    protected static final String STATEMENTS_FILE_PREFIX = "binary_store_";
    protected static final String STATEMENTS_FILENAME_SUFFIX = "_database.properties";
    protected static final String DEFAULT_STATEMENTS_FILE_PATH = STATEMENTS_FILE_PATH + STATEMENTS_FILE_PREFIX + "default"
                                                                 + STATEMENTS_FILENAME_SUFFIX;
    protected static final int DEFAULT_MAX_EXTRACTED_TEXT_LENGTH = 1000;

    private static final Logger LOGGER = Logger.getLogger(Database.class);

    private static final String INSERT_CONTENT_STMT_KEY = "add_content";
    private static final String USED_CONTENT_STMT_KEY = "get_used_content";
    private static final String UNUSED_CONTENT_STMT_KEY = "get_unused_content";
    private static final String MARK_UNUSED_STMT_KEY = "mark_unused";
    private static final String MARK_USED_STMT_KEY = "mark_used";
    private static final String REMOVE_EXPIRED_STMT_KEY = "remove_expired";
    private static final String GET_MIMETYPE_STMT_KEY = "get_mimetype";
    private static final String SET_MIMETYPE_STMT_KEY = "set_mimetype";
    private static final String GET_EXTRACTED_TEXT_STMT_KEY = "get_extracted_text";
    private static final String SET_EXTRACTED_TEXT_STMT_KEY = "set_extracted_text";
    private static final String GET_BINARY_KEYS_STMT_KEY = "get_binary_keys";
    private static final String CREATE_TABLE_STMT_KEY = "create_table";
    private static final String TABLE_EXISTS_STMT_KEY = "table_exists_query";
    
    private static final String EXTRACTED_TEXT_COLUMN_NAME = "ext_text";

    private final String tableName;
    private final int maxExtractedTextLength;

    private Properties statements;

    /**
     * Creates new instance of the database.
     * 
     * @param connection a {@link java.sql.Connection} instance; may not be null
     * @throws java.io.IOException if the statements cannot be processed
     * @throws java.sql.SQLException if the db initialization sequence fails
     */
    protected Database( Connection connection ) throws IOException, SQLException {
        this(connection, null);
    }

    /**
     * Creates new instance of the database.
     * 
     * @param connection a {@link Connection} instance; may not be null
     * @param prefix the prefix for the table name; may be null or blank
     * @throws java.io.IOException if the statements cannot be processed
     * @throws java.sql.SQLException if the db initialization sequence fails
     */
    protected Database(Connection connection,
                       String prefix) throws IOException, SQLException {
        assert connection != null;
        DatabaseMetaData metaData = connection.getMetaData();
        DatabaseType databaseType = DatabaseUtil.determineType(metaData);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Discovered DBMS type for binary store as '{0}' on '{1}", databaseType, metaData.getURL());
        }
        String tablePrefix = prefix == null ? null : prefix.trim();
        this.tableName = tablePrefix != null && tablePrefix.length() != 0 ? tablePrefix + TABLE_NAME : TABLE_NAME;

        initializeStatements(databaseType);
        initializeStorage(connection, databaseType);
        this.maxExtractedTextLength = determineMaxExtractedTextLength(metaData);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Using max length for extracted text '{0}'", maxExtractedTextLength);
        }
    }

    private int determineMaxExtractedTextLength( DatabaseMetaData metaData ) {
        try {
            String tableName = this.tableName;
            if (metaData.storesLowerCaseIdentifiers()) {
                tableName = tableName.toLowerCase();
            } else if (metaData.storesUpperCaseIdentifiers()) {
                tableName = tableName.toUpperCase();
            }
            try (ResultSet resultSet = metaData.getColumns(null, null, tableName,  EXTRACTED_TEXT_COLUMN_NAME)) {
                return resultSet.next() ? resultSet.getInt("COLUMN_SIZE") : DEFAULT_MAX_EXTRACTED_TEXT_LENGTH;
            }
        } catch (SQLException e) {
            LOGGER.debug(e, "Cannot determine the maximum size of the column which holds the extracted text. Defaulting to {0}",
                         DEFAULT_MAX_EXTRACTED_TEXT_LENGTH);
            return DEFAULT_MAX_EXTRACTED_TEXT_LENGTH;
        } 
    }

    private void initializeStatements(DatabaseType databaseType) throws IOException {
        // Load the default statements ...
        String statementsFilename = DEFAULT_STATEMENTS_FILE_PATH;
        InputStream statementStream = getClass().getClassLoader().getResourceAsStream(statementsFilename);
        Properties defaultStatements = new Properties();
        try {
            LOGGER.trace("Loading default statement from '{0}'", statementsFilename);
            defaultStatements.load(statementStream);
        } finally {
            statementStream.close();
        }

        // Look for type-specific statements ...
        statementsFilename = STATEMENTS_FILE_PATH + STATEMENTS_FILE_PREFIX + databaseType.nameString().toLowerCase()
                             + STATEMENTS_FILENAME_SUFFIX;
        statementStream = getClass().getClassLoader().getResourceAsStream(statementsFilename);
        if (statementStream != null) {
            // Try to read the type-specific statements ...
            try {
                LOGGER.trace("Loading DBMS-specific statement from '{0}'", statementsFilename);
                statements = new Properties(defaultStatements);
                statements.load(statementStream);
            } finally {
                statementStream.close();
            }
        } else {
            // No type-specific statements, so just use the default statements ...
            statements = defaultStatements;
            LOGGER.trace("No DBMS-specific statement found in '{0}'", statementsFilename);
        }
    }

    private void initializeStorage(Connection connection, DatabaseType databaseType) throws SQLException {
        // First, prepare a statement to see if the table exists ...
        boolean createTable = true;
        try (PreparedStatement exists = prepareStatement(TABLE_EXISTS_STMT_KEY, connection)) {
            execute(exists);
            createTable = false;
        } catch (SQLException e) {
            // proceed to create the table ...
        } 

        if (createTable) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Unable to find existing table. Attempting to create '{0}' table in {1}", tableName,
                             connection.getMetaData().getURL());
            }
            try (PreparedStatement create = prepareStatement(CREATE_TABLE_STMT_KEY, connection)) {
                execute(create);
            } catch (SQLException e) {
                String msg = JcrI18n.errorCreatingDatabaseTable.text(tableName, databaseType);
                throw new RuntimeException(msg, e);
            }
        }
    }

    protected String getTableName() {
        return tableName;
    }

    protected PreparedStatement prepareStatement( String statementKey,
                                                  Connection connection ) throws SQLException {
        String statementString = statements.getProperty(statementKey);
        statementString = StringUtil.createString(statementString, tableName);
        LOGGER.trace("Preparing statement: {0}", statementString);
        return connection.prepareStatement(statementString);
    }
    
    protected void insertContent( BinaryKey key,
                                  InputStream stream,
                                  long size,
                                  Connection connection ) throws SQLException {
        try (PreparedStatement addContentSql = prepareStatement(INSERT_CONTENT_STMT_KEY, connection)) {
            addContentSql.setString(1, key.toString());
            addContentSql.setTimestamp(2, new java.sql.Timestamp(System.currentTimeMillis()));
            addContentSql.setBinaryStream(3, stream, size);
            execute(addContentSql);
            try {
                // it's not guaranteed that the driver will close the stream, so we mush always close it to prevent read-locks
                stream.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }

    protected boolean contentExists( BinaryKey key, boolean inUse, Connection connection ) throws SQLException {
        try (PreparedStatement readContentStatement = inUse ? 
                                                      prepareStatement(USED_CONTENT_STMT_KEY, connection) :
                                                      prepareStatement(UNUSED_CONTENT_STMT_KEY, connection)) {
            readContentStatement.setString(1, key.toString());
            ResultSet rs = executeQuery(readContentStatement);
            return rs.next();
        } catch (SQLException e) {
            LOGGER.debug("Cannot determine if content exists under key '{0}'", key.toString());
            return false;
        } 
    }
    
    /**
     * Attempts to return the content stream for a given binary value.
     * If a content stream can be read from the database, this method will not close the given connection. However, if either
     * the stream cannot be found or there is an unexpected exception, this method <b>will close</b> the {@code connection}
     * parameter.
     *
     * @param key a {@link org.modeshape.jcr.value.BinaryKey} the key of the binary value, may not be null
     * @param connection a {@link java.sql.Connection} instance, may not be null
     * @return either a stream that wraps the input stream of the binary value and closes the connection and the statement when it
     * terminates or {@code null}, meaning that the binary was not found.
     * @throws SQLException if anything unexpected fails
     */
    protected InputStream readContent( BinaryKey key,
                                       Connection connection ) throws SQLException {
        try {
            // first search the contents which are in use
            InputStream is = readStreamFromStatement(USED_CONTENT_STMT_KEY, key, connection);
            if (is != null) {
                // return the stream without closing the connection
                return is;
            }
            // then search the contents which are in the trash
            is = readStreamFromStatement(UNUSED_CONTENT_STMT_KEY, key, connection);
            if (is != null) {
                // return the stream without closing the connection
                return is;
            }
            // we couldn't find anything, so close the connection
            tryToClose(connection);
            return null;
        } catch (Throwable t) {
            tryToClose(connection);
            throw t;
        }
    }

    private InputStream readStreamFromStatement( String statement, BinaryKey key, Connection connection ) throws SQLException {
        PreparedStatement readContentStatement = prepareStatement(statement, connection);
        try {
            readContentStatement.setString(1, key.toString());
            ResultSet rs = executeQuery(readContentStatement);
            if (!rs.next()) {
                tryToClose(readContentStatement);
                return null;
            }
            return new DatabaseBinaryStream(connection, readContentStatement, rs.getBinaryStream(1));
        } catch (SQLException e) {
            tryToClose(readContentStatement);
            throw e;
        } catch (Throwable t) {
            tryToClose(readContentStatement);
            throw new RuntimeException(t);
        }
    }

    protected void markUnused( Iterable<BinaryKey> keys,
                               Connection connection ) throws SQLException {
        try (PreparedStatement markUnusedSql = prepareStatement(MARK_UNUSED_STMT_KEY, connection)) {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            for (BinaryKey key : keys) {
                markUnusedSql.setTimestamp(1, now);
                markUnusedSql.setString(2, key.toString());
                executeUpdate(markUnusedSql);
            }
        } 
    }

    protected void restoreContent( Connection connection,
                                   Iterable<BinaryKey> keys ) throws SQLException {
        try (PreparedStatement markUsedSql = prepareStatement(MARK_USED_STMT_KEY, connection)) {
            for (BinaryKey key : keys) {
                markUsedSql.setString(1, key.toString());
                executeUpdate(markUsedSql);
            }
        }
    }

    protected void removeExpiredContent( long deadline,
                                         Connection connection ) throws SQLException {
        try (PreparedStatement removedExpiredSql = prepareStatement(REMOVE_EXPIRED_STMT_KEY, connection)) {
            removedExpiredSql.setTimestamp(1, new java.sql.Timestamp(deadline));
            execute(removedExpiredSql);
        }
    }

    protected String getMimeType( BinaryKey key,
                                  Connection connection ) throws SQLException {
        try (PreparedStatement getMimeType = prepareStatement(GET_MIMETYPE_STMT_KEY, connection)) {
            getMimeType.setString(1, key.toString());
            ResultSet rs = executeQuery(getMimeType);
            return rs.next() ? rs.getString(1) : null;
        }
    }

    protected void setMimeType( BinaryKey key,
                                String mimeType,
                                Connection connection ) throws SQLException {
        try (PreparedStatement setMimeTypeSQL = prepareStatement(SET_MIMETYPE_STMT_KEY, connection)) {
            setMimeTypeSQL.setString(1, mimeType);
            setMimeTypeSQL.setString(2, key.toString());
            executeUpdate(setMimeTypeSQL);
        } 
    }

    protected String getExtractedText( BinaryKey key,
                                       Connection connection ) throws SQLException {
        try (PreparedStatement getExtractedTextSql = prepareStatement(GET_EXTRACTED_TEXT_STMT_KEY, connection)) {
            getExtractedTextSql.setString(1, key.toString());
            ResultSet rs = executeQuery(getExtractedTextSql);
            return rs.next() ? rs.getString(1) : null;
        } 
    }

    protected void setExtractedText( BinaryKey key,
                                     String text,
                                     Connection connection ) throws SQLException {
        if (text.length() > maxExtractedTextLength) {
            LOGGER.warn(JcrI18n.warnExtractedTextTooLarge, EXTRACTED_TEXT_COLUMN_NAME, this.maxExtractedTextLength, tableName);
            text = text.substring(0, maxExtractedTextLength);
        }
        try (PreparedStatement setExtractedTextSql = prepareStatement(SET_EXTRACTED_TEXT_STMT_KEY, connection)) {
            setExtractedTextSql.setString(1, text);
            setExtractedTextSql.setString(2, key.toString());
            executeUpdate(setExtractedTextSql);
        }
    }

    protected Set<BinaryKey> getBinaryKeys( Connection connection ) throws SQLException {
        Set<BinaryKey> keys = new HashSet<>();

        try (PreparedStatement getBinaryKeysSql = prepareStatement(GET_BINARY_KEYS_STMT_KEY, connection)) {
            ResultSet rs = executeQuery(getBinaryKeysSql);
            while (rs.next()) {
                keys.add(new BinaryKey(rs.getString(1)));
            }
            return keys;
        } 
    }

    private void execute( PreparedStatement sql ) throws SQLException {
        LOGGER.trace("Executing statement: {0}", sql);
        sql.execute();
    }

    private ResultSet executeQuery( PreparedStatement sql ) throws SQLException {
        LOGGER.trace("Executing query statement: {0}", sql);
        return sql.executeQuery();
    }

    private void executeUpdate( PreparedStatement sql ) throws SQLException {
        LOGGER.trace("Executing update statement: {0}", sql);
        sql.executeUpdate();
    }

    protected class DatabaseBinaryStream extends InputStream {
        private final Connection connection;
        private final PreparedStatement statement;
        private final InputStream jdbcBinaryStream;

        protected DatabaseBinaryStream( Connection connection,
                                        PreparedStatement statement,
                                        InputStream jdbcBinaryStream ) {
            assert connection != null;
            assert statement != null;
            this.connection = connection;
            this.statement = statement;
            // some drivers (notably Oracle) can return a null stream if the stored binary is empty (i.e. has 0 bytes)
            this.jdbcBinaryStream = jdbcBinaryStream != null ? jdbcBinaryStream : new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public int read() throws IOException {
            return jdbcBinaryStream.read();
        }

        @Override
        public int read( byte[] b ) throws IOException {
            return jdbcBinaryStream.read(b);
        }

        @Override
        public int read( byte[] b,
                         int off,
                         int len ) throws IOException {
            return jdbcBinaryStream.read(b, off, len);
        }

        @Override
        public long skip( long n ) throws IOException {
            return jdbcBinaryStream.skip(n);
        }

        @Override
        public int available() throws IOException {
            return jdbcBinaryStream.available();
        }

        @Override
        public void close() {
            tryToClose(statement);
            tryToClose(connection);
        }

        @Override
        public synchronized void mark( int readlimit ) {
            jdbcBinaryStream.mark(readlimit);
        }

        @Override
        public synchronized void reset() throws IOException {
            jdbcBinaryStream.reset();
        }

        @Override
        public boolean markSupported() {
            return jdbcBinaryStream.markSupported();
        }
    }

    private void tryToClose( PreparedStatement statement ) {
        if (statement != null) {
            try {
                statement.close();
            } catch (Throwable t) {
                LOGGER.debug(t, "Cannot close prepared statement");
            }
        }
    }

    private void tryToClose( Connection connection ) {
        if (connection != null) {
            try {
                connection.close();
            } catch (Throwable t) {
                LOGGER.debug(t, "Cannot close connection");
            }
        }
    }
}
