/*-------------------------------------------------------------------------
*
* Copyright (c) 2003-2008, PostgreSQL Global Development Group
* Copyright (c) 2004, Open Cloud Limited.
*
* IDENTIFICATION
*   $PostgreSQL: pgjdbc/org/postgresql/core/v3/QueryExecutorImpl.java,v 1.45 2009/07/01 05:00:40 jurka Exp $
*
*-------------------------------------------------------------------------
*/
package org.postgresql.core.v3;

import org.postgresql.core.*;

import java.util.ArrayList;
import java.util.Vector;
import java.util.HashMap;
import java.util.Properties;

import java.lang.ref.*;

import java.io.IOException;
import java.sql.*;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLWarning;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.postgresql.util.GT;
import org.postgresql.copy.CopyOperation;

/**
 * QueryExecutor implementation for the V3 protocol.
 */
public class QueryExecutorImpl implements QueryExecutor {
	private static my.Debug DEBUG=new my.Debug(my.Debug.QueryExecutor);//我加上的

    public QueryExecutorImpl(ProtocolConnectionImpl protoConnection, PGStream pgStream, Properties info, Logger logger) {
		try {//我加上的
		DEBUG.P(this,"QueryExecutorImpl(3)");

        this.protoConnection = protoConnection;
        this.pgStream = pgStream;
        this.logger = logger;

        if (info.getProperty("allowEncodingChanges") != null) {
            this.allowEncodingChanges = Boolean.valueOf(info.getProperty("allowEncodingChanges")).booleanValue();
        } else {
            this.allowEncodingChanges = false;
        }

		DEBUG.P("allowEncodingChanges="+allowEncodingChanges);

		}finally{//我加上的
		DEBUG.P(0,this,"QueryExecutorImpl(3)");
		}
    }

    /**
     * Supplement to synchronization of public methods on current QueryExecutor.
     * 
     * Necessary for keeping the connection intact between calls to public methods
     * sharing a state such as COPY subprotocol. waitOnLock() must be called at
     * beginning of each connection access point.
     *
     * Public methods sharing that state must then be synchronized among themselves.
     * Normal method synchronization typically suffices for that.
     * 
     * See notes on related methods as well as currentCopy() below.
     */
    private Object lockedFor = null;

    /**
     * Obtain lock over this connection for given object, blocking to wait if necessary.
     * @param obtainer object that gets the lock. Normally current thread.
     * @throws PSQLException when already holding the lock or getting interrupted.
     */
    private void lock(Object obtainer) throws PSQLException {
        if( lockedFor == obtainer )
            throw new PSQLException(GT.tr("Tried to obtain lock while already holding it"), PSQLState.OBJECT_NOT_IN_STATE);
        waitOnLock();
        lockedFor = obtainer;
    }
    
    /**
     * Release lock on this connection presumably held by given object.
     * @param holder object that holds the lock. Normally current thread.
     * @throws PSQLException when this thread does not hold the lock
     */
    private void unlock(Object holder) throws PSQLException {
       if(lockedFor != holder)
           throw new PSQLException(GT.tr("Tried to break lock on database connection"), PSQLState.OBJECT_NOT_IN_STATE);
       lockedFor = null;
       this.notify();
    }

    /**
     * Wait until our lock is released.
     * Execution of a single synchronized method can then continue without further ado.
     * Must be called at beginning of each synchronized public method.
     */
    private void waitOnLock() throws PSQLException {
        while( lockedFor != null ) {
            try {
                this.wait();
            } catch(InterruptedException ie) {
                throw new PSQLException(GT.tr("Interrupted while waiting to obtain lock on database connection"), PSQLState.OBJECT_NOT_IN_STATE, ie);
            }
        }
    }
    
    /**
     * @param holder object assumed to hold the lock
     * @return whether given object actually holds the lock
     */
    boolean hasLock(Object holder) {
        return lockedFor == holder;
    }

    //
    // Query parsing
    //

    public Query createSimpleQuery(String sql) {
		try {//我加上的
		DEBUG.P(this,"createSimpleQuery(1)");

        return parseQuery(sql, false);

		}finally{//我加上的
		DEBUG.P(0,this,"createSimpleQuery(1)");
		}
    }

    public Query createParameterizedQuery(String sql) {
		try {//我加上的
		DEBUG.P(this,"createParameterizedQuery(1)");

        return parseQuery(sql, true);

		}finally{//我加上的
		DEBUG.P(0,this,"createParameterizedQuery(1)");
		}
    }

	//两条sql语句可以用分号隔开(如 sql1;sql2)
    private Query parseQuery(String query, boolean withParameters) {
		try {//我加上的
		DEBUG.P(this,"parseQuery(2)");
		DEBUG.P("query="+query);
		DEBUG.P("withParameters="+withParameters);

        // Parse query and find parameter placeholders;
        // also break the query into separate statements.

        ArrayList statementList = new ArrayList();
        ArrayList fragmentList = new ArrayList(15);

        int fragmentStart = 0;
        int inParen = 0;

        boolean standardConformingStrings = protoConnection.getStandardConformingStrings();

		DEBUG.P("standardConformingStrings="+standardConformingStrings);
        
        char []aChars = query.toCharArray();

        for (int i = 0; i < aChars.length; ++i)
        {
            switch (aChars[i])
            {
            case '\'': // single-quotes
                i = Parser.parseSingleQuotes(aChars, i, standardConformingStrings);
                break;

            case '"': // double-quotes
                i = Parser.parseDoubleQuotes(aChars, i);
                break;

            case '-': // possibly -- style comment
                i = Parser.parseLineComment(aChars, i);
                break;

            case '/': // possibly /* */ style comment
                i = Parser.parseBlockComment(aChars, i);
                break;
            
            case '$': // possibly dollar quote start
                i = Parser.parseDollarQuotes(aChars, i);
                break;

            case '(':
                inParen++;
                break;

            case ')':
                inParen--;
                break;

            case '?':
                if (withParameters)
                {
                    fragmentList.add(query.substring(fragmentStart, i));
                    fragmentStart = i + 1;
                }
                break;

            case ';':
                if (inParen == 0)
                {
                    fragmentList.add(query.substring(fragmentStart, i));
                    fragmentStart = i + 1;
                    if (fragmentList.size() > 1 || ((String)fragmentList.get(0)).trim().length() > 0)
                        statementList.add(fragmentList.toArray(new String[fragmentList.size()]));
                    fragmentList.clear();
                }
                break;

            default:
                break;
            }
        }

		//可能是最后一个?号后的了符串的开始位置，也可能是0(没有?号的sql语句)
		DEBUG.P("fragmentStart="+fragmentStart);

        fragmentList.add(query.substring(fragmentStart));

		DEBUG.P("fragmentList.size()="+fragmentList.size());

        if (fragmentList.size() > 1 || ((String)fragmentList.get(0)).trim().length() > 0)
            statementList.add(fragmentList.toArray(new String[fragmentList.size()]));

        if (statementList.isEmpty())  // Empty query.
            return EMPTY_QUERY;

		DEBUG.P("statementList.size()="+statementList.size());
        if (statementList.size() == 1)
        {
            // Only one statement.
            return new SimpleQuery((String[]) statementList.get(0), protoConnection);
        }

        // Multiple statements.
        SimpleQuery[] subqueries = new SimpleQuery[statementList.size()];
        int[] offsets = new int[statementList.size()];
        int offset = 0;
        for (int i = 0; i < statementList.size(); ++i)
        {
            String[] fragments = (String[]) statementList.get(i);
            offsets[i] = offset;
            subqueries[i] = new SimpleQuery(fragments, protoConnection);
            offset += fragments.length - 1;
        }

        return new CompositeQuery(subqueries, offsets);

		}finally{//我加上的
		DEBUG.P(0,this,"parseQuery(2)");
		}
    }

    //
    // Query execution
    //

	public String myflags(int flags) {
		StringBuilder s = new StringBuilder();
		if ((QUERY_ONESHOT & flags) != 0)
			s.append(" QUERY_ONESHOT");
		if ((QUERY_NO_METADATA & flags) != 0)
			s.append(" QUERY_NO_METADATA");
		if ((QUERY_NO_RESULTS & flags) != 0)
			s.append(" QUERY_NO_RESULTS");
		if ((QUERY_FORWARD_CURSOR & flags) != 0)
			s.append(" QUERY_FORWARD_CURSOR");
		if ((QUERY_SUPPRESS_BEGIN & flags) != 0)
			s.append(" QUERY_SUPPRESS_BEGIN");
		if ((QUERY_DESCRIBE_ONLY & flags) != 0)
			s.append(" QUERY_DESCRIBE_ONLY");
		if ((QUERY_BOTH_ROWS_AND_STATUS & flags) != 0)
			s.append(" QUERY_BOTH_ROWS_AND_STATUS");

		return s.toString();
	}
    public synchronized void execute(Query query,
                                     ParameterList parameters,
                                     ResultHandler handler,
                                     int maxRows,
                                     int fetchSize,
                                     int flags)
    throws SQLException
    {
		try {//我加上的
		DEBUG.P(this,"execute(6)");
		DEBUG.P("query="+query);
		DEBUG.P("parameters="+parameters);
		DEBUG.P("handler="+handler);
		DEBUG.P("maxRows="+maxRows);
		DEBUG.P("fetchSize="+fetchSize);
		DEBUG.P("flags="+myflags(flags));

        waitOnLock();
        if (logger.logDebug())
        {
            logger.debug("simple execute, handler=" + handler +
                         ", maxRows=" + maxRows + ", fetchSize=" + fetchSize + ", flags=" + flags);
        }

        if (parameters == null)
            parameters = SimpleQuery.NO_PARAMETERS;

        boolean describeOnly = (QUERY_DESCRIBE_ONLY & flags) != 0;

        ((V3ParameterList)parameters).convertFunctionOutParameters();

		DEBUG.P("describeOnly="+describeOnly);
        // Check parameters are all set..
        if (!describeOnly) //检查所有的?是否设置了相应值(OUT类型除外)
            ((V3ParameterList)parameters).checkAllParametersSet();

        try
        {
            try
            {
				//Preamble导言
                handler = sendQueryPreamble(handler, flags);
                ErrorTrackingResultHandler trackingHandler = new ErrorTrackingResultHandler(handler);
                queryCount = 0;
                sendQuery((V3Query)query, (V3ParameterList)parameters, maxRows, fetchSize, flags, trackingHandler);
                sendSync();
                processResults(handler, flags);
            }
            catch (PGBindException se)
            {
                // There are three causes of this error, an
                // invalid total Bind message length, a
                // BinaryStream that cannot provide the amount
                // of data claimed by the length arugment, and
                // a BinaryStream that throws an Exception
                // when reading.
                //
                // We simply do not send the Execute message
                // so we can just continue on as if nothing
                // has happened.  Perhaps we need to
                // introduce an error here to force the
                // caller to rollback if there is a
                // transaction in progress?
                //
                sendSync();
                processResults(handler, flags);
                handler.handleError(new PSQLException(GT.tr("Unable to bind parameter values for statement."), PSQLState.INVALID_PARAMETER_VALUE, se.getIOException()));
            }
        }
        catch (IOException e)
        {
            protoConnection.close();
            handler.handleError(new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
        }

        handler.handleCompletion();

		}finally{//我加上的
		DEBUG.P(0,this,"execute(6)");
		}
    }

    // Deadlock avoidance:
    //
    // It's possible for the send and receive streams to get "deadlocked" against each other since
    // we do not have a separate thread. The scenario is this: we have two streams:
    //
    //   driver -> TCP buffering -> server
    //   server -> TCP buffering -> driver
    //
    // The server behaviour is roughly:
    //  while true:
    //   read message
    //   execute message
    //   write results
    //
    // If the server -> driver stream has a full buffer, the write will block.
    // If the driver is still writing when this happens, and the driver -> server
    // stream also fills up, we deadlock: the driver is blocked on write() waiting
    // for the server to read some more data, and the server is blocked on write()
    // waiting for the driver to read some more data.
    //
    // To avoid this, we guess at how many queries we can send before the server ->
    // driver stream's buffer is full (MAX_BUFFERED_QUERIES). This is the point where
    // the server blocks on write and stops reading data. If we reach this point, we
    // force a Sync message and read pending data from the server until ReadyForQuery,
    // then go back to writing more queries unless we saw an error.
    //
    // This is not 100% reliable -- it's only done in the batch-query case and only
    // at a reasonably high level (per query, not per message), and it's only an estimate
    // -- so it might break. To do it correctly in all cases would seem to require a
    // separate send or receive thread as we can only do the Sync-and-read-results
    // operation at particular points, and also as we don't really know how much data
    // the server is sending.

    // Assume 64k server->client buffering and 250 bytes response per query (conservative).
    private static final int MAX_BUFFERED_QUERIES = (64000 / 250);

    // Helper handler that tracks error status.
    private static class ErrorTrackingResultHandler implements ResultHandler {
        private final ResultHandler delegateHandler;
        private boolean sawError = false;

        ErrorTrackingResultHandler(ResultHandler delegateHandler) {
            this.delegateHandler = delegateHandler;
        }

        public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
            delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
        }

        public void handleCommandStatus(String status, int updateCount, long insertOID) {
            delegateHandler.handleCommandStatus(status, updateCount, insertOID);
        }

        public void handleWarning(SQLWarning warning) {
            delegateHandler.handleWarning(warning);
        }

        public void handleError(SQLException error) {
            sawError = true;
            delegateHandler.handleError(error);
        }

        public void handleCompletion() throws SQLException {
            delegateHandler.handleCompletion();
        }

        boolean hasErrors() {
            return sawError;
        }
    }

    public synchronized void execute(Query[] queries,
                                     ParameterList[] parameterLists,
                                     ResultHandler handler,
                                     int maxRows,
                                     int fetchSize,
                                     int flags)
    throws SQLException
    {
		try {//我加上的
		DEBUG.P(this,"execute(6s)");
		DEBUG.PA("queries",queries);
		DEBUG.PA("parameterLists",parameterLists);
		DEBUG.P("handler="+handler);
		DEBUG.P("maxRows="+maxRows);
		DEBUG.P("fetchSize="+fetchSize);
		DEBUG.P("flags="+myflags(flags));
		DEBUG.P(1);

        waitOnLock();
        if (logger.logDebug())
        {
            logger.debug("batch execute " + queries.length + " queries, handler=" + handler +
                         ", maxRows=" + maxRows + ", fetchSize=" + fetchSize + ", flags=" + flags);
        }

        boolean describeOnly = (QUERY_DESCRIBE_ONLY & flags) != 0;
        DEBUG.P("describeOnly="+describeOnly);

		// Check parameters and resolve OIDs.
        if (!describeOnly) {
            for (int i = 0; i < parameterLists.length; ++i)
            {
                if (parameterLists[i] != null)
                    ((V3ParameterList)parameterLists[i]).checkAllParametersSet();
            }
        }

        try
        {
            handler = sendQueryPreamble(handler, flags);
            ErrorTrackingResultHandler trackingHandler = new ErrorTrackingResultHandler(handler);
            queryCount = 0;

            for (int i = 0; i < queries.length; ++i)
            {
                V3Query query = (V3Query)queries[i];
                V3ParameterList parameters = (V3ParameterList)parameterLists[i];
                if (parameters == null)
                    parameters = SimpleQuery.NO_PARAMETERS;

                sendQuery(query, parameters, maxRows, fetchSize, flags, trackingHandler);

                if (trackingHandler.hasErrors())
                    break;
            }

			DEBUG.P("(!trackingHandler.hasErrors())="+(!trackingHandler.hasErrors()));

            if (!trackingHandler.hasErrors())
            {
                sendSync();
                processResults(handler, flags);
            }
        }
        catch (IOException e)
        {
            protoConnection.close();
            handler.handleError(new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
        }

        handler.handleCompletion();

		}finally{//我加上的
		DEBUG.P(0,this,"execute(6s)");
		}
    }

	//发送BEGIN
    private ResultHandler sendQueryPreamble(final ResultHandler delegateHandler, int flags) throws IOException {
        try {//我加上的
		DEBUG.P(this,"sendQueryPreamble(2)");

		// First, send CloseStatements for finalized SimpleQueries that had statement names assigned.
        processDeadParsedQueries();
        processDeadPortals();

		DEBUG.P("protoConnection.getTransactionState()="+protoConnection.getTransactionState());

        // Send BEGIN on first statement in transaction.
        if ((flags & QueryExecutor.QUERY_SUPPRESS_BEGIN) != 0 ||
                protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            return delegateHandler;

        sendOneQuery(beginTransactionQuery, SimpleQuery.NO_PARAMETERS, 0, 0, QueryExecutor.QUERY_NO_METADATA);

        // Insert a handler that intercepts the BEGIN.
        return new ResultHandler() {
                   private boolean sawBegin = false;

                   public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
                       if (sawBegin)
                           delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
                   }

                   public void handleCommandStatus(String status, int updateCount, long insertOID) {
					   try {//我加上的
					   DEBUG.P(this,"handleCommandStatus(3)");
					   DEBUG.P("status="+status);
					   DEBUG.P("updateCount="+updateCount);
					   DEBUG.P("insertOID="+insertOID);
					   DEBUG.P("sawBegin="+sawBegin);

					   //当conn.setAutoCommit(false)时，会自动发BEGIN命令，
					   //然后会得到CommandStatus
					   //status=BEGIN
					   //updateCount=0
					   //insertOID=0
					   //sawBegin=false

                       if (!sawBegin)
                       {
                           sawBegin = true;
                           if (!status.equals("BEGIN"))
                               handleError(new PSQLException(GT.tr("Expected command status BEGIN, got {0}.", status),
                                                             PSQLState.PROTOCOL_VIOLATION));
                       }
                       else
                       {
                           delegateHandler.handleCommandStatus(status, updateCount, insertOID);
                       }

					   }finally{//我加上的
					   DEBUG.P(0,this,"handleCommandStatus(3)");
					   }
                   }

                   public void handleWarning(SQLWarning warning) {
                       delegateHandler.handleWarning(warning);
                   }

                   public void handleError(SQLException error) {
                       delegateHandler.handleError(error);
                   }

                   public void handleCompletion() throws SQLException{
                       delegateHandler.handleCompletion();
                   }
               };
		}finally{//我加上的
		DEBUG.P(0,this,"sendQueryPreamble(2)");
		}
	}

    //
    // Fastpath
    //

    public synchronized byte[]
    fastpathCall(int fnid, ParameterList parameters, boolean suppressBegin) throws SQLException {
        waitOnLock();
        if (!suppressBegin)
        {
            doSubprotocolBegin();
        }
        try
        {
            sendFastpathCall(fnid, (SimpleParameterList)parameters);
            return receiveFastpathResult();
        }
        catch (IOException ioe)
        {
            protoConnection.close();
            throw new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    public void doSubprotocolBegin() throws SQLException {
        if (protoConnection.getTransactionState() == ProtocolConnection.TRANSACTION_IDLE)
        {

            if (logger.logDebug())
                logger.debug("Issuing BEGIN before fastpath or copy call.");

            ResultHandler handler = new ResultHandler() {
                                        private boolean sawBegin = false;
                                        private SQLException sqle = null;

                                        public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
                                        }

                                        public void handleCommandStatus(String status, int updateCount, long insertOID) {
                                            if (!sawBegin)
                                            {
                                                if (!status.equals("BEGIN"))
                                                    handleError(new PSQLException(GT.tr("Expected command status BEGIN, got {0}.", status),
                                                                                  PSQLState.PROTOCOL_VIOLATION));
                                                sawBegin = true;
                                            }
                                            else
                                            {
                                                handleError(new PSQLException(GT.tr("Unexpected command status: {0}.", status),
                                                                              PSQLState.PROTOCOL_VIOLATION));
                                            }
                                        }

                                        public void handleWarning(SQLWarning warning) {
                                            // we don't want to ignore warnings and it would be tricky
                                            // to chain them back to the connection, so since we don't
                                            // expect to get them in the first place, we just consider
                                            // them errors.
                                            handleError(warning);
                                        }

                                        public void handleError(SQLException error) {
                                            if (sqle == null)
                                            {
                                                sqle = error;
                                            }
                                            else
                                            {
                                                sqle.setNextException(error);
                                            }
                                        }

                                        public void handleCompletion() throws SQLException{
                                            if (sqle != null)
                                                throw sqle;
                                        }
                                    };

            try
            {
                sendOneQuery(beginTransactionQuery, SimpleQuery.NO_PARAMETERS, 0, 0, QueryExecutor.QUERY_NO_METADATA);
                sendSync();
                processResults(handler, 0);
            }
            catch (IOException ioe)
            {
                throw new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
            }
        }

    }

    public ParameterList createFastpathParameters(int count) {
        return new SimpleParameterList(count, protoConnection);
    }

    private void sendFastpathCall(int fnid, SimpleParameterList params) throws SQLException, IOException {
        if (logger.logDebug())
            logger.debug(" FE=> FunctionCall(" + fnid + ", " + params.getParameterCount() + " params)");

        //
        // Total size = 4 (length)
        //            + 4 (function OID)
        //            + 2 (format code count) + N * 2 (format codes)
        //            + 2 (parameter count) + encodedSize (parameters)
        //            + 2 (result format)

        int paramCount = params.getParameterCount();
        int encodedSize = 0;
        for (int i = 1; i <= paramCount; ++i)
        {
            if (params.isNull(i))
                encodedSize += 4;
            else
                encodedSize += 4 + params.getV3Length(i);
        }


        pgStream.SendChar('F');
        pgStream.SendInteger4(4 + 4 + 2 + 2 * paramCount + 2 + encodedSize + 2);
        pgStream.SendInteger4(fnid);
        pgStream.SendInteger2(paramCount);
        for (int i = 1; i <= paramCount; ++i)
            pgStream.SendInteger2(params.isBinary(i) ? 1 : 0);
        pgStream.SendInteger2(paramCount);
        for (int i = 1; i <= paramCount; i++)
        {
            if (params.isNull(i))
            {
                pgStream.SendInteger4( -1);
            }
            else
            {
                pgStream.SendInteger4(params.getV3Length(i));   // Parameter size
                params.writeV3Value(i, pgStream);
            }
        }
        pgStream.SendInteger2(1); // Binary result format
        pgStream.flush();
    }

    public synchronized void processNotifies() throws SQLException {
        waitOnLock();
        // Asynchronous notifies only arrive when we are not in a transaction
        if (protoConnection.getTransactionState() != ProtocolConnection.TRANSACTION_IDLE)
            return;

        try {
            while (pgStream.hasMessagePending()) {
                int c = pgStream.ReceiveChar();
                switch (c) {
                case 'A':  // Asynchronous Notify
                    receiveAsyncNotify();
                    break;
                case 'E':  // Error Response (response to pretty much everything; backend then skips until Sync)
                    throw receiveErrorResponse();
                    // break;
                case 'N':  // Notice Response (warnings / info)
                    SQLWarning warning = receiveNoticeResponse();
                    protoConnection.addWarning(warning);
                    break;
                default:
                    throw new PSQLException(GT.tr("Unknown Response Type {0}.", new Character((char) c)), PSQLState.CONNECTION_FAILURE);
                }
            }
        } catch (IOException ioe) {
            throw new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }
    
    private byte[] receiveFastpathResult() throws IOException, SQLException {
        boolean endQuery = false;
        SQLException error = null;
        byte[] returnValue = null;

        while (!endQuery)
        {
            int c = pgStream.ReceiveChar();
            switch (c)
            {
            case 'A':  // Asynchronous Notify
                receiveAsyncNotify();
                break;

            case 'E':  // Error Response (response to pretty much everything; backend then skips until Sync)
                SQLException newError = receiveErrorResponse();
                if (error == null)
                    error = newError;
                else
                    error.setNextException(newError);
                // keep processing
                break;

            case 'N':  // Notice Response (warnings / info)
                SQLWarning warning = receiveNoticeResponse();
                protoConnection.addWarning(warning);
                break;

            case 'Z':    // Ready For Query (eventual response to Sync)
                receiveRFQ();
                endQuery = true;
                break;

            case 'V':  // FunctionCallResponse
                int msgLen = pgStream.ReceiveInteger4();
                int valueLen = pgStream.ReceiveInteger4();

                if (logger.logDebug())
                    logger.debug(" <=BE FunctionCallResponse(" + valueLen + " bytes)");

                if (valueLen != -1)
                {
                    byte buf[] = new byte[valueLen];
                    pgStream.Receive(buf, 0, valueLen);
                    returnValue = buf;
                }

                break;

            default:
                throw new PSQLException(GT.tr("Unknown Response Type {0}.", new Character((char) c)), PSQLState.CONNECTION_FAILURE);
            }

        }

        // did we get an error during this query?
        if (error != null)
            throw error;

        return returnValue;
    }

    //
    // Copy subprotocol implementation
    //

    /**
     * Sends given query to BE to start, initialize and lock connection for a CopyOperation.
     * @param sql COPY FROM STDIN / COPY TO STDOUT statement
     * @return CopyIn or CopyOut operation object
     * @throws SQLException on failure
     */
    public synchronized CopyOperation startCopy(String sql, boolean suppressBegin) throws SQLException {
        waitOnLock();
        if (!suppressBegin) {
            doSubprotocolBegin();
        }
        byte buf[] = Utils.encodeUTF8(sql);

        try {
            if (logger.logDebug())
                logger.debug(" FE=> Query(CopyStart)");

            pgStream.SendChar('Q');
            pgStream.SendInteger4(buf.length + 4 + 1);
            pgStream.Send(buf);
            pgStream.SendChar(0);
            pgStream.flush();

            return processCopyResults(null, true); // expect a CopyInResponse or CopyOutResponse to our query above
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when starting copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    /**
     * Locks connection and calls initializer for a new CopyOperation
     * Called via startCopy -> processCopyResults
     * @param op an unitialized CopyOperation
     * @throws SQLException on locking failure
     * @throws IOException on database connection failure
     */
    private synchronized void initCopy(CopyOperationImpl op) throws SQLException, IOException {
        pgStream.ReceiveInteger4(); // length not used
        int rowFormat = pgStream.ReceiveChar();
        int numFields = pgStream.ReceiveInteger2();
        int[] fieldFormats = new int[numFields];

        for(int i=0; i<numFields; i++)
            fieldFormats[i] = pgStream.ReceiveInteger2();

        lock(op);
        op.init(this, rowFormat, fieldFormats);
    }

    /**
     * Finishes a copy operation and unlocks connection discarding any exchanged data.
     * @param op the copy operation presumably currently holding lock on this connection
     * @throws SQLException on any additional failure
     */
    public void cancelCopy(CopyOperationImpl op) throws SQLException {
        if(!hasLock(op))
            throw new PSQLException(GT.tr("Tried to cancel an inactive copy operation"), PSQLState.OBJECT_NOT_IN_STATE);

        SQLException error = null;
        int errors = 0;

        try {
            if(op instanceof CopyInImpl) {
                synchronized (this) {
                    if (logger.logDebug()) {
                        logger.debug("FE => CopyFail");
                    }
                    final byte[] msg = Utils.encodeUTF8("Copy cancel requested");
                    pgStream.SendChar('f'); // CopyFail
                    pgStream.SendInteger4(5 + msg.length);
                    pgStream.Send(msg);
                    pgStream.SendChar(0);
                    pgStream.flush();
                    do {
                        try {
                            processCopyResults(op, true); // discard rest of input
                        } catch(SQLException se) { // expected error response to failing copy
                            errors++;
                            if( error != null ) {
                                SQLException e = se, next;
                                while( (next = e.getNextException()) != null )
                                    e = next;
                                e.setNextException(error);
                            }
                            error = se; 
                        }
                    } while(hasLock(op));
                }
            } else if (op instanceof CopyOutImpl) {
                protoConnection.sendQueryCancel();
            }

        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when canceling copy operation"), PSQLState.CONNECTION_FAILURE, ioe);
        }

        if (op instanceof CopyInImpl) {
            if(errors < 1) {
                throw new PSQLException(GT.tr("Missing expected error response to copy cancel request"), PSQLState.COMMUNICATION_ERROR);
            } else if(errors > 1) {
                throw new PSQLException(GT.tr("Got {0} error responses to single copy cancel request", String.valueOf(errors)), PSQLState.COMMUNICATION_ERROR, error);
            }
        }
    }

    /**
     * Finishes writing to copy and unlocks connection
     * @param op the copy operation presumably currently holding lock on this connection
     * @return number of rows updated for server versions 8.2 or newer
     * @throws SQLException on failure
     */
    public synchronized long endCopy(CopyInImpl op) throws SQLException {
        if(!hasLock(op))
                throw new PSQLException(GT.tr("Tried to end inactive copy"), PSQLState.OBJECT_NOT_IN_STATE);

        try {
            if (logger.logDebug())
                logger.debug(" FE=> CopyDone");

            pgStream.SendChar('c'); // CopyDone
            pgStream.SendInteger4(4);
            pgStream.flush();

            processCopyResults(op, true);
            return op.getHandledRowCount();
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when ending copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    /**
     * Sends data during a live COPY IN operation. Only unlocks the connection if server
     * suddenly returns CommandComplete, which should not happen
     * @param op the CopyIn operation presumably currently holding lock on this connection
     * @param data bytes to send
     * @param off index of first byte to send (usually 0)
     * @param siz number of bytes to send (usually data.length)
     * @throws SQLException on failure
     */
    public synchronized void writeToCopy(CopyInImpl op, byte[] data, int off, int siz) throws SQLException {
        if(!hasLock(op))
            throw new PSQLException(GT.tr("Tried to write to an inactive copy operation"), PSQLState.OBJECT_NOT_IN_STATE);

        if (logger.logDebug())
            logger.debug(" FE=> CopyData(" + siz + ")");

        try {
            pgStream.SendChar('d');
            pgStream.SendInteger4(siz + 4);
            pgStream.Send(data, off, siz);

            processCopyResults(op, false); // collect any pending notifications without blocking
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when writing to copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    public synchronized void flushCopy(CopyInImpl op) throws SQLException {
        if(!hasLock(op))
            throw new PSQLException(GT.tr("Tried to write to an inactive copy operation"), PSQLState.OBJECT_NOT_IN_STATE);

        try {
            pgStream.flush();
            processCopyResults(op, false); // collect any pending notifications without blocking
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when writing to copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    /**
     * Blocks to wait for a row of data to be received from server on an active copy operation
     * Connection gets unlocked by processCopyResults() at end of operation
     * @param op the copy operation presumably currently holding lock on this connection
     * @throws SQLException on any failure
     */
    synchronized void readFromCopy(CopyOutImpl op) throws SQLException {
        if(!hasLock(op))
            throw new PSQLException(GT.tr("Tried to read from inactive copy"), PSQLState.OBJECT_NOT_IN_STATE);

        try {
            processCopyResults(op, true); // expect a call to handleCopydata() to store the data
        } catch(IOException ioe) {
            throw new PSQLException(GT.tr("Database connection failed when reading from copy"), PSQLState.CONNECTION_FAILURE, ioe);
        }
    }

    /**
     * Handles copy sub protocol responses from server.
     * Unlocks at end of sub protocol,
     * so operations on pgStream or QueryExecutor are not allowed in a method after calling this!
     * @param block whether to block waiting for input
     * @return 
     *  CopyIn when COPY FROM STDIN starts;
     *  CopyOut when COPY TO STDOUT starts;
     *  null when copy ends;
     *  otherwise, the operation given as parameter.
     * @throws SQLException in case of misuse
     * @throws IOException from the underlying connection
     */
    CopyOperationImpl processCopyResults(CopyOperationImpl op, boolean block) throws SQLException, IOException {

        boolean endReceiving = false;
        SQLException error = null, errors = null;
        int len;

        while( !endReceiving && (block || pgStream.hasMessagePending()) ) {

            // There is a bug in the server's implementation of the copy
            // protocol.  It returns command complete immediately upon
            // receiving the EOF marker in the binary protocol,
            // potentially before we've issued CopyDone.  When we are not
            // blocking, we don't think we are done, so we hold off on
            // processing command complete and any subsequent messages
            // until we actually are done with the copy.
            //
            if (!block) {
                int c = pgStream.PeekChar();
                if (c == 'C') // CommandComplete
                {
                    if (logger.logDebug())
                        logger.debug(" <=BE CommandStatus, Ignored until CopyDone");
                    break;
                }
            }

            int c = pgStream.ReceiveChar();
            switch(c) {

            case 'A': // Asynchronous Notify

                if (logger.logDebug())
                    logger.debug(" <=BE Asynchronous Notification while copying");

                receiveAsyncNotify();
                break;

            case 'N': // Notice Response

                if (logger.logDebug())
                    logger.debug(" <=BE Notification while copying");

                protoConnection.addWarning(receiveNoticeResponse());
                break;

            case 'C': // Command Complete

                String status = receiveCommandStatus();

                try {
                    if(op == null)
                        throw new PSQLException(GT.tr("Received CommandComplete ''{0}'' without an active copy operation", status), PSQLState.OBJECT_NOT_IN_STATE);
                    op.handleCommandStatus(status);
                } catch(SQLException se) {
                    error = se;
                }

                block = true;
                break;

            case 'E': // ErrorMessage (expected response to CopyFail)

                error = receiveErrorResponse();
                // We've received the error and we now expect to receive
                // Ready for query, but we must block because it might still be
                // on the wire and not here yet.
                block = true;
                break;

            case 'G':  // CopyInResponse
                
                if (logger.logDebug())
                    logger.debug(" <=BE CopyInResponse");

                if(op != null)
                    error = new PSQLException(GT.tr("Got CopyInResponse from server during an active {0}", op.getClass().getName()), PSQLState.OBJECT_NOT_IN_STATE);

                op = new CopyInImpl();
                initCopy(op);
                endReceiving = true;
                break;
                
            case 'H':  // CopyOutResponse
                
                if (logger.logDebug())
                    logger.debug(" <=BE CopyOutResponse");

                if(op != null)
                    error = new PSQLException(GT.tr("Got CopyOutResponse from server during an active {0}", op.getClass().getName()), PSQLState.OBJECT_NOT_IN_STATE);

                op = new CopyOutImpl();
                initCopy(op);
                endReceiving = true;
                break;

            case 'd': // CopyData

                if (logger.logDebug())
                    logger.debug(" <=BE CopyData");

                len = pgStream.ReceiveInteger4() - 4;
                byte[] buf = pgStream.Receive(len);
                if(op == null) {
                    error = new PSQLException(GT.tr("Got CopyData without an active copy operation"), PSQLState.OBJECT_NOT_IN_STATE);
                } else if (!(op instanceof CopyOutImpl)) {
                    error = new PSQLException(GT.tr("Unexpected copydata from server for {0}",
                            op.getClass().getName()), PSQLState.COMMUNICATION_ERROR);
                } else {
                    ((CopyOutImpl)op).handleCopydata(buf);
                }
                endReceiving = true;
                break;

            case 'c': // CopyDone (expected after all copydata received)

                if (logger.logDebug())
                    logger.debug(" <=BE CopyDone");
                
                len = pgStream.ReceiveInteger4() - 4;
                if(len > 0)
                    pgStream.Receive(len); // not in specification; should never appear

                if(!(op instanceof CopyOutImpl))
                    error = new PSQLException("Got CopyDone while not copying from server", PSQLState.OBJECT_NOT_IN_STATE);
                
                // keep receiving since we expect a CommandComplete
                block = true;
                break;

            case 'Z': // ReadyForQuery: After FE:CopyDone => BE:CommandComplete

                receiveRFQ();
                if(hasLock(op))
                    unlock(op);
                op = null;
                endReceiving = true;
                break;

            // If the user sends a non-copy query, we've got to handle some additional things.
            //
            case 'T':  // Row Description (response to Describe)
                if (logger.logDebug())
                    logger.debug(" <=BE RowDescription (during copy ignored)");


                skipMessage();
                break;

            case 'D':  // DataRow
                if (logger.logDebug())
                    logger.debug(" <=BE DataRow (during copy ignored)");

                skipMessage();
                break;

            default:
                throw new IOException(GT.tr("Unexpected packet type during copy: {0}", Integer.toString(c)));
            }

            // Collect errors into a neat chain for completeness
            if(error != null) {
                if(errors != null)
                    error.setNextException(errors);
                errors = error;
                error = null;
            }
        }

        if(errors != null)
            throw errors;

        return op;
    }


    /*
     * Send a query to the backend.
     */
    private void sendQuery(V3Query query, V3ParameterList parameters, int maxRows, int fetchSize, int flags, ErrorTrackingResultHandler trackingHandler) throws IOException, SQLException {
        try {//我加上的
		DEBUG.P(this,"sendQuery(6)");

		// Now the query itself.
        SimpleQuery[] subqueries = query.getSubqueries();
        SimpleParameterList[] subparams = parameters.getSubparams();

		DEBUG.PA("subqueries",subqueries);
		DEBUG.PA("subparams",subparams);
        if (subqueries == null)
        {
            ++queryCount;

			DEBUG.P("queryCount="+queryCount);
			DEBUG.P("MAX_BUFFERED_QUERIES="+MAX_BUFFERED_QUERIES);

            if (queryCount >= MAX_BUFFERED_QUERIES)
            {
                sendSync();
                processResults(trackingHandler, flags);

                queryCount = 0;
            }

			DEBUG.P("(!trackingHandler.hasErrors())="+(!trackingHandler.hasErrors()));

             // If we saw errors, don't send anything more.
            if (!trackingHandler.hasErrors())
                sendOneQuery((SimpleQuery)query, (SimpleParameterList)parameters, maxRows, fetchSize, flags);
        }
        else
        {
            for (int i = 0; i < subqueries.length; ++i)
            {
                ++queryCount;
                if (queryCount >= MAX_BUFFERED_QUERIES)
                {
                    sendSync();
                    processResults(trackingHandler, flags);

                    // If we saw errors, don't send anything more.
                    if (trackingHandler.hasErrors())
                        break;

                    queryCount = 0;
                }

                // In the situation where parameters is already
                // NO_PARAMETERS it cannot know the correct
                // number of array elements to return in the
                // above call to getSubparams(), so it must
                // return null which we check for here.
                //
                SimpleParameterList subparam = SimpleQuery.NO_PARAMETERS;
                if (subparams != null)
                {
                    subparam = subparams[i];
                }
                sendOneQuery(subqueries[i], subparam, maxRows, fetchSize, flags);
            }
        }

		}finally{//我加上的
		DEBUG.P(0,this,"sendQuery(6)");
		}
    }

    //
    // Message sending
    //

    private void sendSync() throws IOException {
        if (logger.logDebug())
            logger.debug(" FE=> Sync");

        pgStream.SendChar('S');     // Sync
        pgStream.SendInteger4(4); // Length
        pgStream.flush();
    }

    private void sendParse(SimpleQuery query, SimpleParameterList params, boolean oneShot) throws IOException {
		try {//我加上的
		DEBUG.P(this,"sendParse(3)");
		DEBUG.P("oneShot="+oneShot);

        // Already parsed, or we have a Parse pending and the types are right?
        int[] typeOIDs = params.getTypeOIDs();

		DEBUG.PA("typeOIDs",typeOIDs);

		//如果已经解析过就直接返回了，比如PreparedStatement在执行批量操作的时候
        if (query.isPreparedFor(typeOIDs))
            return;

        // Clean up any existing statement, as we can't use it.
        query.unprepare();
        processDeadParsedQueries();

        String statementName = null;
        if (!oneShot)
        {
            // Generate a statement name to use.
            statementName = "S_" + (nextUniqueID++);

            // And prepare the new statement.
            // NB: Must clone the OID array, as it's a direct reference to
            // the SimpleParameterList's internal array that might be modified
            // under us.
            query.setStatementName(statementName);
            query.setStatementTypes((int[])typeOIDs.clone());
        }

		DEBUG.P("statementName="+statementName);

        byte[] encodedStatementName = query.getEncodedStatementName();
        String[] fragments = query.getFragments();

		DEBUG.PA("fragments",fragments);

        if (logger.logDebug())
        {
            StringBuffer sbuf = new StringBuffer(" FE=> Parse(stmt=" + statementName + ",query=\"");
            for (int i = 0; i < fragments.length; ++i)
            {
                if (i > 0)
                    sbuf.append("$" + i);
                sbuf.append(fragments[i]);
            }
            sbuf.append("\",oids={");
            for (int i = 1; i <= params.getParameterCount(); ++i)
            {
                if (i != 1)
                    sbuf.append(",");
                sbuf.append("" + params.getTypeOID(i));
            }
            sbuf.append("})");
            logger.debug(sbuf.toString());
        }

        //
        // Send Parse.
        //

		//sql子串比?多1,所以parts的元素个数只要[fragments.length * 2 - 1]
        byte[][] parts = new byte[fragments.length * 2 - 1][];
        int j = 0;
        int encodedSize = 0;

        // Total size = 4 (size field)
        //            + N + 1 (statement name, zero-terminated)
        //            + N + 1 (query, zero terminated)
        //            + 2 (parameter count) + N * 4 (parameter types)
        // original query: "frag0 ? frag1 ? frag2"
        // fragments: { "frag0", "frag1", "frag2" }
        // output: "frag0 $1 frag1 $2 frag2"
        for (int i = 0; i < fragments.length; ++i)
        {
            if (i != 0)
            {
                parts[j] = Utils.encodeUTF8("$" + i);
                encodedSize += parts[j].length;
                ++j;
            }

            parts[j] = Utils.encodeUTF8(fragments[i]);
            encodedSize += parts[j].length;
            ++j;
        }

        encodedSize = 4
                      + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1
                      + encodedSize + 1
                      + 2 + 4 * params.getParameterCount();

        pgStream.SendChar('P'); // Parse
        pgStream.SendInteger4(encodedSize);
        if (encodedStatementName != null)
            pgStream.Send(encodedStatementName);
        pgStream.SendChar(0);   // End of statement name
        for (int i = 0; i < parts.length; ++i)
        { // Query string
            pgStream.Send(parts[i]);
        }
        pgStream.SendChar(0);       // End of query string.
        pgStream.SendInteger2(params.getParameterCount());       // # of parameter types specified
        for (int i = 1; i <= params.getParameterCount(); ++i)
            pgStream.SendInteger4(params.getTypeOID(i));

        pendingParseQueue.add(new Object[]{query, query.getStatementName()});

		}finally{//我加上的
		DEBUG.P(0,this,"sendParse(3)");
		}
    }

    private void sendBind(SimpleQuery query, SimpleParameterList params, Portal portal) throws IOException {
        try {//我加上的
		DEBUG.P(this,"sendBind(3)");
		DEBUG.P("query="+query);
		DEBUG.P("params="+params);
		DEBUG.P("portal="+portal);

		//
        // Send Bind.
        //

        String statementName = query.getStatementName();
        byte[] encodedStatementName = query.getEncodedStatementName();
        byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());

        if (logger.logDebug())
        {
            StringBuffer sbuf = new StringBuffer(" FE=> Bind(stmt=" + statementName + ",portal=" + portal);
            for (int i = 1; i <= params.getParameterCount(); ++i)
            {
                sbuf.append(",$" + i + "=<" + params.toString(i) + ">");
            }
            sbuf.append(")");
            logger.debug(sbuf.toString());
        }

        // Total size = 4 (size field) + N + 1 (destination portal)
        //            + N + 1 (statement name)
        //            + 2 (param format code count) + N * 2 (format codes)
        //            + 2 (param value count) + N (encoded param value size)
        //            + 2 (result format code count, 0)
        long encodedSize = 0;
        for (int i = 1; i <= params.getParameterCount(); ++i)
        {
            if (params.isNull(i))
                encodedSize += 4;
            else
                encodedSize += (long)4 + params.getV3Length(i);
        }

        encodedSize = 4
                      + (encodedPortalName == null ? 0 : encodedPortalName.length) + 1
                      + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1
                      + 2 + params.getParameterCount() * 2
                      + 2 + encodedSize
                      + 2;

        // backend's MaxAllocSize is the largest message that can
        // be received from a client.  If we have a bigger value
        // from either very large parameters or incorrent length
        // descriptions of setXXXStream we do not send the bind
        // messsage.
        //
        if (encodedSize > 0x3fffffff)
        {
            throw new PGBindException(new IOException(GT.tr("Bind message length {0} too long.  This can be caused by very large or incorrect length specifications on InputStream parameters.", new Long(encodedSize))));
        }

        pgStream.SendChar('B');                  // Bind
        pgStream.SendInteger4((int)encodedSize);      // Message size
        if (encodedPortalName != null)
            pgStream.Send(encodedPortalName);    // Destination portal name.
        pgStream.SendChar(0);                    // End of portal name.
        if (encodedStatementName != null)
            pgStream.Send(encodedStatementName); // Source statement name.
        pgStream.SendChar(0);                    // End of statement name.

        pgStream.SendInteger2(params.getParameterCount());      // # of parameter format codes
        for (int i = 1; i <= params.getParameterCount(); ++i)
            pgStream.SendInteger2(params.isBinary(i) ? 1 : 0);  // Parameter format code

        pgStream.SendInteger2(params.getParameterCount());      // # of parameter values

        // If an error occurs when reading a stream we have to
        // continue pumping out data to match the length we
        // said we would.  Once we've done that we throw
        // this exception.  Multiple exceptions can occur and
        // it really doesn't matter which one is reported back
        // to the caller.
        //
        PGBindException bindException = null;

        for (int i = 1; i <= params.getParameterCount(); ++i)
        {
            if (params.isNull(i))
                pgStream.SendInteger4( -1);                      // Magic size of -1 means NULL
            else
            {
                pgStream.SendInteger4(params.getV3Length(i));   // Parameter size
                try
                {
                    params.writeV3Value(i, pgStream);                 // Parameter value
                }
                catch (PGBindException be)
                {
                    bindException = be;
                }
            }
        }

        pgStream.SendInteger2(0);   // # of result format codes (0)

        pendingBindQueue.add(portal);

        if (bindException != null)
        {
            throw bindException;
        }

		}finally{//我加上的
		DEBUG.P(0,this,"sendBind(3)");
		}
    }

    private void sendDescribePortal(SimpleQuery query, Portal portal) throws IOException {
        //
        // Send Describe.
        //

        if (logger.logDebug())
        {
            logger.debug(" FE=> Describe(portal=" + portal + ")");
        }

        byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());

        // Total size = 4 (size field) + 1 (describe type, 'P') + N + 1 (portal name)
        int encodedSize = 4 + 1 + (encodedPortalName == null ? 0 : encodedPortalName.length) + 1;

        pgStream.SendChar('D');               // Describe
        pgStream.SendInteger4(encodedSize); // message size
        pgStream.SendChar('P');               // Describe (Portal)
        if (encodedPortalName != null)
            pgStream.Send(encodedPortalName); // portal name to close
        pgStream.SendChar(0);                 // end of portal name

        pendingDescribePortalQueue.add(query);
        query.setPortalDescribed(true);
    }

    private void sendDescribeStatement(SimpleQuery query, SimpleParameterList params, boolean describeOnly) throws IOException {
        // Send Statement Describe

        if (logger.logDebug())
        {
            logger.debug(" FE=> Describe(statement=" + query.getStatementName()+")");
        }

        byte[] encodedStatementName = query.getEncodedStatementName();

        // Total size = 4 (size field) + 1 (describe type, 'S') + N + 1 (portal name)
        int encodedSize = 4 + 1 + (encodedStatementName == null ? 0 : encodedStatementName.length) + 1;

        pgStream.SendChar('D');                     // Describe
        pgStream.SendInteger4(encodedSize);         // Message size
        pgStream.SendChar('S');                     // Describe (Statement);
        if (encodedStatementName != null)
            pgStream.Send(encodedStatementName);    // Statement name
        pgStream.SendChar(0);                       // end message

        pendingDescribeStatementQueue.add(new Object[]{query, params, new Boolean(describeOnly), query.getStatementName()});
        pendingDescribePortalQueue.add(query);
        query.setStatementDescribed(true);
        query.setPortalDescribed(true);
    }

    private void sendExecute(SimpleQuery query, Portal portal, int limit) throws IOException {
		try {//我加上的
		DEBUG.P(this,"sendExecute(3)");

        //
        // Send Execute.
        //

        if (logger.logDebug())
        {
            logger.debug(" FE=> Execute(portal=" + portal + ",limit=" + limit + ")");
        }

        byte[] encodedPortalName = (portal == null ? null : portal.getEncodedPortalName());
        int encodedSize = (encodedPortalName == null ? 0 : encodedPortalName.length);

        // Total size = 4 (size field) + 1 + N (source portal) + 4 (max rows)
        pgStream.SendChar('E');              // Execute
        pgStream.SendInteger4(4 + 1 + encodedSize + 4);  // message size
        if (encodedPortalName != null)
            pgStream.Send(encodedPortalName); // portal name
        pgStream.SendChar(0);                 // portal name terminator
        pgStream.SendInteger4(limit);       // row limit

        pendingExecuteQueue.add(new Object[] { query, portal });

		DEBUG.P("pendingExecuteQueue.size="+pendingExecuteQueue.size());

		}finally{//我加上的
		DEBUG.P(0,this,"sendExecute(3)");
		}
    }

    private void sendClosePortal(String portalName) throws IOException {
        //
        // Send Close.
        //

        if (logger.logDebug())
        {
            logger.debug(" FE=> ClosePortal(" + portalName + ")");
        }

        byte[] encodedPortalName = (portalName == null ? null : Utils.encodeUTF8(portalName));
        int encodedSize = (encodedPortalName == null ? 0 : encodedPortalName.length);

        // Total size = 4 (size field) + 1 (close type, 'P') + 1 + N (portal name)
        pgStream.SendChar('C');              // Close
        pgStream.SendInteger4(4 + 1 + 1 + encodedSize);  // message size
        pgStream.SendChar('P');              // Close (Portal)
        if (encodedPortalName != null)
            pgStream.Send(encodedPortalName);
        pgStream.SendChar(0);                // unnamed portal
    }

    private void sendCloseStatement(String statementName) throws IOException {
        //
        // Send Close.
        //

        if (logger.logDebug())
        {
            logger.debug(" FE=> CloseStatement(" + statementName + ")");
        }

        byte[] encodedStatementName = Utils.encodeUTF8(statementName);

        // Total size = 4 (size field) + 1 (close type, 'S') + N + 1 (statement name)
        pgStream.SendChar('C');              // Close
        pgStream.SendInteger4(4 + 1 + encodedStatementName.length + 1);  // message size
        pgStream.SendChar('S');              // Close (Statement)
        pgStream.Send(encodedStatementName); // statement to close
        pgStream.SendChar(0);                // statement name terminator
    }

    // sendOneQuery sends a single statement via the extended query protocol.
    // Per the FE/BE docs this is essentially the same as how a simple query runs
    // (except that it generates some extra acknowledgement messages, and we
    // can send several queries before doing the Sync)
    //
    //   Parse     S_n from "query string with parameter placeholders"; skipped if already done previously or if oneshot
    //   Bind      C_n from S_n plus parameters (or from unnamed statement for oneshot queries)
    //   Describe  C_n; skipped if caller doesn't want metadata
    //   Execute   C_n with maxRows limit; maxRows = 1 if caller doesn't want results
    // (above repeats once per call to sendOneQuery)
    //   Sync      (sent by caller)
    //
    private void sendOneQuery(SimpleQuery query, SimpleParameterList params, int maxRows, int fetchSize, int flags) throws IOException {
		try {//我加上的
		DEBUG.P(this,"sendOneQuery(5)");
		DEBUG.P("query="+query);
		DEBUG.P("params="+params);
		DEBUG.P("maxRows="+maxRows);
		DEBUG.P("fetchSize="+fetchSize);
		DEBUG.P("flags="+myflags(flags));
		DEBUG.P(1);

        // nb: if we decide to use a portal (usePortal == true) we must also use a named statement
        // (oneShot == false) as otherwise the portal will be closed under us unexpectedly when
        // the unnamed statement is next reused.

        boolean noResults = (flags & QueryExecutor.QUERY_NO_RESULTS) != 0;
        boolean noMeta = (flags & QueryExecutor.QUERY_NO_METADATA) != 0;
        boolean describeOnly = (flags & QueryExecutor.QUERY_DESCRIBE_ONLY) != 0;
        boolean usePortal = (flags & QueryExecutor.QUERY_FORWARD_CURSOR) != 0 && !noResults && !noMeta && fetchSize > 0 && !describeOnly;
        boolean oneShot = (flags & QueryExecutor.QUERY_ONESHOT) != 0 && !usePortal;

		DEBUG.P("noResults="+noResults);
		DEBUG.P("noMeta="+noMeta);
		DEBUG.P("describeOnly="+describeOnly);
		DEBUG.P("usePortal="+usePortal);
		DEBUG.P("oneShot="+oneShot);
		DEBUG.P(1);

        // Work out how many rows to fetch in this pass.

        int rows;
        if (noResults)
        {
            rows = 1;             // We're discarding any results anyway, so limit data transfer to a minimum
        }
        else if (!usePortal)
        {
            rows = maxRows;       // Not using a portal -- fetchSize is irrelevant
        }
        else if (maxRows != 0 && fetchSize > maxRows)
        {
            rows = maxRows;       // fetchSize > maxRows, use maxRows (nb: fetchSize cannot be 0 if usePortal == true)
        }
        else
        {
            rows = fetchSize;     // maxRows > fetchSize
        }

		DEBUG.P("rows="+rows);

        sendParse(query, params, oneShot);

        // Must do this after sendParse to pick up any changes to the
        // query's state.
        //
        boolean queryHasUnknown = query.hasUnresolvedTypes();
        boolean paramsHasUnknown = params.hasUnresolvedTypes();

        boolean describeStatement = describeOnly || (!oneShot && paramsHasUnknown && queryHasUnknown && !query.isStatementDescribed());

		DEBUG.P("queryHasUnknown="+queryHasUnknown);
		DEBUG.P("paramsHasUnknown="+paramsHasUnknown);
		DEBUG.P("describeStatement="+describeStatement);

        if (!describeStatement && paramsHasUnknown && !queryHasUnknown)
        {
            int queryOIDs[] = query.getStatementTypes();
            int paramOIDs[] = params.getTypeOIDs();
            for (int i=0; i<paramOIDs.length; i++) {
                // Only supply type information when there isn't any
                // already, don't arbitrarily overwrite user supplied
                // type information.
                if (paramOIDs[i] == Oid.UNSPECIFIED) {
                    params.setResolvedType(i+1, queryOIDs[i]);
                }
            }
        }

        if (describeStatement) {
            sendDescribeStatement(query, params, describeOnly);
            if (describeOnly)
                return;
        }

        // Construct a new portal if needed.
        Portal portal = null;

		DEBUG.P("usePortal="+usePortal);
        if (usePortal)
        {
            String portalName = "C_" + (nextUniqueID++);

			DEBUG.P("portalName="+portalName);
            portal = new Portal(query, portalName);
        }

        sendBind(query, params, portal);

        // A statement describe will also output a RowDescription,
        // so don't reissue it here if we've already done so.
        //
        if (!noMeta && !describeStatement && !query.isPortalDescribed())
            sendDescribePortal(query, portal);

        sendExecute(query, portal, rows);

		}finally{//我加上的
		DEBUG.P(0,this,"sendOneQuery(5)");
		}
    }

    //
    // Garbage collection of parsed statements.
    //
    // When a statement is successfully parsed, registerParsedQuery is called.
    // This creates a PhantomReference referring to the "owner" of the statement
    // (the originating Query object) and inserts that reference as a key in
    // parsedQueryMap. The values of parsedQueryMap are the corresponding allocated
    // statement names. The originating Query object also holds a reference to the
    // PhantomReference.
    //
    // When the owning Query object is closed, it enqueues and clears the associated
    // PhantomReference.
    //
    // If the owning Query object becomes unreachable (see java.lang.ref javadoc) before
    // being closed, the corresponding PhantomReference is enqueued on
    // parsedQueryCleanupQueue. In the Sun JVM, phantom references are only enqueued
    // when a GC occurs, so this is not necessarily prompt but should eventually happen.
    //
    // Periodically (currently, just before query execution), the parsedQueryCleanupQueue
    // is polled. For each enqueued PhantomReference we find, we remove the corresponding
    // entry from parsedQueryMap, obtaining the name of the underlying statement in the
    // process. Then we send a message to the backend to deallocate that statement.
    //

    private final HashMap parsedQueryMap = new HashMap();
    private final ReferenceQueue parsedQueryCleanupQueue = new ReferenceQueue();

    private void registerParsedQuery(SimpleQuery query, String statementName) {
        if (statementName == null)
            return ;

        PhantomReference cleanupRef = new PhantomReference(query, parsedQueryCleanupQueue);
        parsedQueryMap.put(cleanupRef, statementName);
        query.setCleanupRef(cleanupRef);
    }

    private void processDeadParsedQueries() throws IOException {
		try {//我加上的
		DEBUG.P(this,"processDeadParsedQueries()");

        PhantomReference deadQuery;
        while ((deadQuery = (PhantomReference)parsedQueryCleanupQueue.poll()) != null)
        {
            String statementName = (String)parsedQueryMap.remove(deadQuery);
            
			DEBUG.P("statementName="+statementName);

			sendCloseStatement(statementName);
            deadQuery.clear();
        }

		}finally{//我加上的
		DEBUG.P(0,this,"processDeadParsedQueries()");
		}
    }

    //
    // Essentially the same strategy is used for the cleanup of portals.
    // Note that each Portal holds a reference to the corresponding Query
    // that generated it, so the Query won't be collected (and the statement
    // closed) until all the Portals are, too. This is required by the mechanics
    // of the backend protocol: when a statement is closed, all dependent portals
    // are also closed.
    //

    private final HashMap openPortalMap = new HashMap();
    private final ReferenceQueue openPortalCleanupQueue = new ReferenceQueue();

    private void registerOpenPortal(Portal portal) {
        if (portal == null)
            return ; // Using the unnamed portal.

        String portalName = portal.getPortalName();
        PhantomReference cleanupRef = new PhantomReference(portal, openPortalCleanupQueue);
        openPortalMap.put(cleanupRef, portalName);
        portal.setCleanupRef(cleanupRef);
    }

    private void processDeadPortals() throws IOException {
        try {//我加上的
		DEBUG.P(this,"processDeadPortals()");

		PhantomReference deadPortal;
        while ((deadPortal = (PhantomReference)openPortalCleanupQueue.poll()) != null)
        {
            String portalName = (String)openPortalMap.remove(deadPortal);

			DEBUG.P("portalName="+portalName);

            sendClosePortal(portalName);
            deadPortal.clear();
        }

		}finally{//我加上的
		DEBUG.P(0,this,"processDeadPortals()");
		}
    }

    protected void processResults(ResultHandler handler, int flags) throws IOException {
		try {//我加上的
		DEBUG.P(this,"processResults(2)");

        boolean noResults = (flags & QueryExecutor.QUERY_NO_RESULTS) != 0;
        boolean bothRowsAndStatus = (flags & QueryExecutor.QUERY_BOTH_ROWS_AND_STATUS) != 0;

		DEBUG.P("noResults="+noResults);
		DEBUG.P("bothRowsAndStatus="+bothRowsAndStatus);

        Vector tuples = null;

        int len;
        int c;
        boolean endQuery = false;

        // At the end of a command execution we have the CommandComplete
        // message to tell us we're done, but with a describeOnly command
        // we have no real flag to let us know we're done.  We've got to
        // look for the next RowDescription or NoData message and return
        // from there.
        boolean doneAfterRowDescNoData = false;

        int parseIndex = 0;
        int describeIndex = 0;
        int describePortalIndex = 0;
        int bindIndex = 0;
        int executeIndex = 0;

        while (!endQuery)
        {
            c = pgStream.ReceiveChar();
			DEBUG.P("c="+((char)c));
            switch (c)
            {
            case 'A':  // Asynchronous Notify
                receiveAsyncNotify();
                break;

            case '1':    // Parse Complete (response to Parse)
                pgStream.ReceiveInteger4(); // len, discarded

                Object[] parsedQueryAndStatement = (Object[])pendingParseQueue.get(parseIndex++);

                SimpleQuery parsedQuery = (SimpleQuery)parsedQueryAndStatement[0];
                String parsedStatementName = (String)parsedQueryAndStatement[1];

                if (logger.logDebug())
                    logger.debug(" <=BE ParseComplete [" + parsedStatementName + "]");

                registerParsedQuery(parsedQuery, parsedStatementName);
                break;
			//比如调用getParameterMetaData()时
            case 't':    // ParameterDescription
                pgStream.ReceiveInteger4(); // len, discarded

                if (logger.logDebug())
                    logger.debug(" <=BE ParameterDescription");

                {
                    Object describeData[] = (Object[])pendingDescribeStatementQueue.get(describeIndex);
                    SimpleQuery query = (SimpleQuery)describeData[0];
                    SimpleParameterList params = (SimpleParameterList)describeData[1];
                    boolean describeOnly = ((Boolean)describeData[2]).booleanValue();
                    String origStatementName = (String)describeData[3];

					DEBUG.PA("describeData",describeData);

                    int numParams = pgStream.ReceiveInteger2();

					DEBUG.P("numParams="+numParams);

                    for (int i=1; i<=numParams; i++) {
                        int typeOid = pgStream.ReceiveInteger4();

						DEBUG.P("typeOid="+typeOid);
                        params.setResolvedType(i, typeOid);
                    }

                    // Since we can issue multiple Parse and DescribeStatement
                    // messages in a single network trip, we need to make
                    // sure the describe results we requested are still
                    // applicable to the latest parsed query.
                    //
                    if ((origStatementName == null && query.getStatementName() == null) || (origStatementName != null && origStatementName.equals(query.getStatementName()))) {
                        query.setStatementTypes((int[])params.getTypeOIDs().clone());
                    }

                    if (describeOnly)
                        doneAfterRowDescNoData = true;
                    else
                        describeIndex++;
                }
                break;

            case '2':    // Bind Complete  (response to Bind)
                pgStream.ReceiveInteger4(); // len, discarded

                Portal boundPortal = (Portal)pendingBindQueue.get(bindIndex++);
                if (logger.logDebug())
                    logger.debug(" <=BE BindComplete [" + boundPortal + "]");

                registerOpenPortal(boundPortal);
                break;

            case '3':    // Close Complete (response to Close)
                pgStream.ReceiveInteger4(); // len, discarded
                if (logger.logDebug())
                    logger.debug(" <=BE CloseComplete");
                break;

            case 'n':    // No Data        (response to Describe)
                pgStream.ReceiveInteger4(); // len, discarded
                if (logger.logDebug())
                    logger.debug(" <=BE NoData");

                describePortalIndex++;

                if (doneAfterRowDescNoData) {
                    Object describeData[] = (Object[])pendingDescribeStatementQueue.get(describeIndex++);
                    SimpleQuery currentQuery = (SimpleQuery)describeData[0];

                    Field[] fields = currentQuery.getFields();

                    if (fields != null)
                    { // There was a resultset.
                        tuples = new Vector();
                        handler.handleResultRows(currentQuery, fields, tuples, null);
                        tuples = null;
                    }
                }
                break;

            case 's':    // Portal Suspended (end of Execute)
                // nb: this appears *instead* of CommandStatus.
                // Must be a SELECT if we suspended, so don't worry about it.

                pgStream.ReceiveInteger4(); // len, discarded
                if (logger.logDebug())
                    logger.debug(" <=BE PortalSuspended");

                {
                    Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
                    SimpleQuery currentQuery = (SimpleQuery)executeData[0];
                    Portal currentPortal = (Portal)executeData[1];

                    Field[] fields = currentQuery.getFields();
                    if (fields != null && !noResults && tuples == null)
                        tuples = new Vector();

                    handler.handleResultRows(currentQuery, fields, tuples, currentPortal);
                }

                tuples = null;
                break;

            case 'C':  // Command Status (end of Execute)
                // Handle status.
                String status = receiveCommandStatus();

                doneAfterRowDescNoData = false;

                {
                    Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
                    SimpleQuery currentQuery = (SimpleQuery)executeData[0];
                    Portal currentPortal = (Portal)executeData[1];

                    Field[] fields = currentQuery.getFields();
                    
					DEBUG.PA("fields",fields);

					if (fields != null && !noResults && tuples == null)
                        tuples = new Vector();

                    if (fields != null || tuples != null)
                    { // There was a resultset.
                        handler.handleResultRows(currentQuery, fields, tuples, null);
                        tuples = null;

                        if (bothRowsAndStatus)
                            interpretCommandStatus(status, handler);
                    }
                    else
                    {
                        interpretCommandStatus(status, handler);
                    }

                    if (currentPortal != null)
                        currentPortal.close();
                }
                break;

            case 'D':  // Data Transfer (ongoing Execute response)
                Object tuple = null;
                try {
                    tuple = pgStream.ReceiveTupleV3();
                } catch(OutOfMemoryError oome) {
                    if (!noResults) {
                        handler.handleError(new PSQLException(GT.tr("Ran out of memory retrieving query results."), PSQLState.OUT_OF_MEMORY, oome));
                    }
                }


                if (!noResults)
                {
                    if (tuples == null)
                        tuples = new Vector();
                    tuples.addElement(tuple);
                }

                if (logger.logDebug())
                    logger.debug(" <=BE DataRow");

                break;

            case 'E':  // Error Response (response to pretty much everything; backend then skips until Sync)
                SQLException error = receiveErrorResponse();
                handler.handleError(error);

                // keep processing
                break;

            case 'I':  // Empty Query (end of Execute)
                pgStream.ReceiveInteger4();

                if (logger.logDebug())
                    logger.debug(" <=BE EmptyQuery");

                {
                    Object[] executeData = (Object[])pendingExecuteQueue.get(executeIndex++);
                    Query currentQuery = (Query)executeData[0];
                    Portal currentPortal = (Portal)executeData[1];
                    handler.handleCommandStatus("EMPTY", 0, 0);
                    if (currentPortal != null)
                        currentPortal.close();
                }

                break;

            case 'N':  // Notice Response
                SQLWarning warning = receiveNoticeResponse();
                handler.handleWarning(warning);
                break;

            case 'S':    // Parameter Status
                {
                    int l_len = pgStream.ReceiveInteger4();
                    String name = pgStream.ReceiveString();
                    String value = pgStream.ReceiveString();
                    if (logger.logDebug())
                        logger.debug(" <=BE ParameterStatus(" + name + " = " + value + ")");

                    if (name.equals("client_encoding") && !(value.equalsIgnoreCase("UNICODE") || value.equalsIgnoreCase("UTF8")) && !allowEncodingChanges)
                    {
                        protoConnection.close(); // we're screwed now; we can't trust any subsequent string.
                        handler.handleError(new PSQLException(GT.tr("The server''s client_encoding parameter was changed to {0}. The JDBC driver requires client_encoding to be UNICODE for correct operation.", value), PSQLState.CONNECTION_FAILURE));
                        endQuery = true;
                    }

                    if (name.equals("DateStyle") && !value.startsWith("ISO,"))
                    {
                        protoConnection.close(); // we're screwed now; we can't trust any subsequent date.
                        handler.handleError(new PSQLException(GT.tr("The server''s DateStyle parameter was changed to {0}. The JDBC driver requires DateStyle to begin with ISO for correct operation.", value), PSQLState.CONNECTION_FAILURE));
                        endQuery = true;
                    }
                    
                    if (name.equals("standard_conforming_strings"))
                    {
                        if (value.equals("on"))
                            protoConnection.setStandardConformingStrings(true);
                        else if (value.equals("off"))
                            protoConnection.setStandardConformingStrings(false);
                        else
                        {
                            protoConnection.close(); // we're screwed now; we don't know how to escape string literals
                            handler.handleError(new PSQLException(GT.tr("The server''s standard_conforming_strings parameter was reported as {0}. The JDBC driver expected on or off.", value), PSQLState.CONNECTION_FAILURE));
                            endQuery = true;
                        }
                    }
                }
                break;

            case 'T':  // Row Description (response to Describe)
                Field[] fields = receiveFields();
                tuples = new Vector();

                SimpleQuery query = (SimpleQuery)pendingDescribePortalQueue.get(describePortalIndex++);
                query.setFields(fields);

				DEBUG.P("describePortalIndex="+describePortalIndex);
				DEBUG.P("doneAfterRowDescNoData="+doneAfterRowDescNoData);
                if (doneAfterRowDescNoData) {
                    Object describeData[] = (Object[])pendingDescribeStatementQueue.get(describeIndex++);
                    Query currentQuery = (Query)describeData[0];

                    handler.handleResultRows(currentQuery, fields, tuples, null);
                    tuples = null;
                }
                break;

            case 'Z':    // Ready For Query (eventual response to Sync)
                receiveRFQ();
                endQuery = true;

                // Reset the statement name of Parses that failed.
                while (parseIndex < pendingParseQueue.size())
                {
                    Object[] failedQueryAndStatement = (Object[])pendingParseQueue.get(parseIndex++);
                    SimpleQuery failedQuery = (SimpleQuery)failedQueryAndStatement[0];
                    failedQuery.unprepare();
                }

                pendingParseQueue.clear();              // No more ParseComplete messages expected.
                pendingDescribeStatementQueue.clear();  // No more ParameterDescription messages expected.
                pendingDescribePortalQueue.clear();     // No more RowDescription messages expected.
                pendingBindQueue.clear();               // No more BindComplete messages expected.
                pendingExecuteQueue.clear();            // No more query executions expected.
                break;

            case 'G':  // CopyInResponse
                if (logger.logDebug()) {
                    logger.debug(" <=BE CopyInResponse");
                    logger.debug(" FE=> CopyFail");
                }

                // COPY sub-protocol is not implemented yet
                // We'll send a CopyFail message for COPY FROM STDIN so that
                // server does not wait for the data.

                byte[] buf = Utils.encodeUTF8("The JDBC driver currently does not support COPY operations.");
                pgStream.SendChar('f');
                pgStream.SendInteger4(buf.length + 4 + 1);
                pgStream.Send(buf);
                pgStream.SendChar(0);
                pgStream.flush();
                sendSync();     // send sync message
                skipMessage();  // skip the response message
                break;

            case 'H':  // CopyOutResponse
                if (logger.logDebug())
                    logger.debug(" <=BE CopyOutResponse");

                skipMessage();
                // In case of CopyOutResponse, we cannot abort data transfer,
                // so just throw an error and ignore CopyData messages
                handler.handleError(new PSQLException(GT.tr("The driver currently does not support COPY operations."), PSQLState.NOT_IMPLEMENTED));
                break;

            case 'c':  // CopyDone
                skipMessage();
                if (logger.logDebug()) {
                    logger.debug(" <=BE CopyDone");
                }
                break;

            case 'd':  // CopyData
                skipMessage();
                if (logger.logDebug()) {
                    logger.debug(" <=BE CopyData");
                }
                break;

            default:
                throw new IOException("Unexpected packet type: " + c);
            }

        }

		}finally{//我加上的
		DEBUG.P(0,this,"processResults(2)");
		}
    }

    /**
     * Ignore the response message by reading the message length and skipping
     * over those bytes in the communication stream.
     */
    private void skipMessage() throws IOException {
        int l_len = pgStream.ReceiveInteger4();        
        // skip l_len-4 (length includes the 4 bytes for message length itself
        pgStream.Skip(l_len - 4);
    }
 
    public synchronized void fetch(ResultCursor cursor, ResultHandler handler, int fetchSize)
    throws SQLException {
		try {//我加上的
		DEBUG.P(this,"fetch(3)");
		DEBUG.P("cursor="+cursor);
		DEBUG.P("handler="+handler);
		DEBUG.P("fetchSize="+fetchSize);

        waitOnLock();
        final Portal portal = (Portal)cursor;

        // Insert a ResultHandler that turns bare command statuses into empty datasets
        // (if the fetch returns no rows, we see just a CommandStatus..)
        final ResultHandler delegateHandler = handler;
        handler = new ResultHandler() {
                      public void handleResultRows(Query fromQuery, Field[] fields, Vector tuples, ResultCursor cursor) {
                          delegateHandler.handleResultRows(fromQuery, fields, tuples, cursor);
                      }

                      public void handleCommandStatus(String status, int updateCount, long insertOID) {
                          handleResultRows(portal.getQuery(), null, new Vector(), null);
                      }

                      public void handleWarning(SQLWarning warning) {
                          delegateHandler.handleWarning(warning);
                      }

                      public void handleError(SQLException error) {
                          delegateHandler.handleError(error);
                      }

                      public void handleCompletion() throws SQLException{
                          delegateHandler.handleCompletion();
                      }
                  };

        // Now actually run it.

        try
        {
            processDeadParsedQueries();
            processDeadPortals();

            sendExecute(portal.getQuery(), portal, fetchSize);
            sendSync();

            processResults(handler, 0);
        }
        catch (IOException e)
        {
            protoConnection.close();
            handler.handleError(new PSQLException(GT.tr("An I/O error occured while sending to the backend."), PSQLState.CONNECTION_FAILURE, e));
        }

        handler.handleCompletion();

		}finally{//我加上的
		DEBUG.P(0,this,"fetch(3)");
		}
    }

    /*
     * Receive the field descriptions from the back end.
     */
    private Field[] receiveFields() throws IOException
    {
		try {//我加上的
		DEBUG.P(this,"receiveFields()");

        int l_msgSize = pgStream.ReceiveInteger4();
        int size = pgStream.ReceiveInteger2();
        Field[] fields = new Field[size];

        if (logger.logDebug())
            logger.debug(" <=BE RowDescription(" + size + ")");
        for (int i = 0; i < fields.length; i++)
        {
            String columnLabel = pgStream.ReceiveString();
            int tableOid = pgStream.ReceiveInteger4();
            short positionInTable = (short)pgStream.ReceiveInteger2();
            int typeOid = pgStream.ReceiveInteger4();
            int typeLength = pgStream.ReceiveInteger2();
            int typeModifier = pgStream.ReceiveInteger4();
            int formatType = pgStream.ReceiveInteger2();
            fields[i] = new Field(columnLabel,
                                  null,  /* name not yet determined */
                                  typeOid, typeLength, typeModifier, tableOid, positionInTable);
            fields[i].setFormat(formatType);
        }

		DEBUG.PA("fields",fields);
        return fields;

		}finally{//我加上的
		DEBUG.P(0,this,"receiveFields()");
		}
    }

    private void receiveAsyncNotify() throws IOException {
        int msglen = pgStream.ReceiveInteger4();
        int pid = pgStream.ReceiveInteger4();
        String msg = pgStream.ReceiveString();
        String param = pgStream.ReceiveString(); //通常是""
        protoConnection.addNotification(new org.postgresql.core.Notification(msg, pid, param));

        if (logger.logDebug())
            logger.debug(" <=BE AsyncNotify(" + pid + "," + msg + "," + param + ")");
    }

    private SQLException receiveErrorResponse() throws IOException {
        // it's possible to get more than one error message for a query
        // see libpq comments wrt backend closing a connection
        // so, append messages to a string buffer and keep processing
        // check at the bottom to see if we need to throw an exception

        int elen = pgStream.ReceiveInteger4();
        String totalMessage = pgStream.ReceiveString(elen - 4);
        ServerErrorMessage errorMsg = new ServerErrorMessage(totalMessage, logger.getLogLevel());

        if (logger.logDebug())
            logger.debug(" <=BE ErrorMessage(" + errorMsg.toString() + ")");

        return new PSQLException(errorMsg);
    }

    private SQLWarning receiveNoticeResponse() throws IOException {
        int nlen = pgStream.ReceiveInteger4();
        ServerErrorMessage warnMsg = new ServerErrorMessage(pgStream.ReceiveString(nlen - 4), logger.getLogLevel());

        if (logger.logDebug())
            logger.debug(" <=BE NoticeResponse(" + warnMsg.toString() + ")");

        return new PSQLWarning(warnMsg);
    }

    private String receiveCommandStatus() throws IOException {
        //TODO: better handle the msg len
        int l_len = pgStream.ReceiveInteger4();
        //read l_len -5 bytes (-4 for l_len and -1 for trailing \0)
        String status = pgStream.ReceiveString(l_len - 5);
        //now read and discard the trailing \0
        pgStream.Receive(1);

        if (logger.logDebug())
            logger.debug(" <=BE CommandStatus(" + status + ")");

        return status;
    }

    private void interpretCommandStatus(String status, ResultHandler handler) {
        try {//我加上的
		DEBUG.P(this,"interpretCommandStatus(2)");

		//如: status=INSERT 0 1(第一个数字对应insert_oid,第二个是update_count)
		DEBUG.P("status="+status);

		int update_count = 0;
        long insert_oid = 0; //当是自动生成的key时，这个也是0，生成的记录会放到第一个结果集中了

        if (status.startsWith("INSERT") || status.startsWith("UPDATE") || status.startsWith("DELETE") || status.startsWith("MOVE"))
        {
            try
            {
				//加1是为了跳过数字前的空格
                update_count = Integer.parseInt(status.substring(1 + status.lastIndexOf(' ')));
                if (status.startsWith("INSERT"))
                    insert_oid = Long.parseLong(status.substring(1 + status.indexOf(' '),
                                                status.lastIndexOf(' ')));
            }
            catch (NumberFormatException nfe)
            {
                handler.handleError(new PSQLException(GT.tr("Unable to interpret the update count in command completion tag: {0}.", status), PSQLState.CONNECTION_FAILURE));
                return ;
            }
        }

		DEBUG.P("update_count="+update_count);
		DEBUG.P("insert_oid="+insert_oid);

        handler.handleCommandStatus(status, update_count, insert_oid);

		}finally{//我加上的
		DEBUG.P(0,this,"interpretCommandStatus(2)");
		}
    }

    private void receiveRFQ() throws IOException {
        if (pgStream.ReceiveInteger4() != 5)
            throw new IOException("unexpected length of ReadyForQuery message");

        char tStatus = (char)pgStream.ReceiveChar();
        if (logger.logDebug())
            logger.debug(" <=BE ReadyForQuery(" + tStatus + ")");

        // Update connection state.
        switch (tStatus)
        {
        case 'I':
            protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_IDLE);
            break;
        case 'T':
            protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_OPEN);
            break;
        case 'E':
            protoConnection.setTransactionState(ProtocolConnection.TRANSACTION_FAILED);
            break;
        default:
            throw new IOException("unexpected transaction state in ReadyForQuery message: " + (int)tStatus);
        }
    }

    private final ArrayList pendingParseQueue = new ArrayList(); // list of SimpleQuery instances
    private final ArrayList pendingBindQueue = new ArrayList(); // list of Portal instances
    private final ArrayList pendingExecuteQueue = new ArrayList(); // list of {SimpleQuery,Portal} object arrays
    private final ArrayList pendingDescribeStatementQueue = new ArrayList(); // list of {SimpleQuery, SimpleParameterList, Boolean} object arrays
    private final ArrayList pendingDescribePortalQueue = new ArrayList(); // list of SimpleQuery

    private long nextUniqueID = 1;
    private final ProtocolConnectionImpl protoConnection;
    private final PGStream pgStream;
    private final Logger logger;
    private final boolean allowEncodingChanges;

    /**
     * The number of queries executed so far without processing any results.
     * Used to avoid deadlocks, see MAX_BUFFERED_QUERIES.
     */
    private int queryCount;

    private final SimpleQuery beginTransactionQuery = new SimpleQuery(new String[] { "BEGIN" }, null);

    private final static SimpleQuery EMPTY_QUERY = new SimpleQuery(new String[] { "" }, null);
}
