/*
 * This software is released under the Apache Software Licence. See
 * package.html for details. The latest version is available at
 * http://proxool.sourceforge.net
 */
package org.logicalcobwebs.proxool;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.DecimalFormat;
import java.util.Collection;
import java.util.Vector;

/**
 * This is where most things happen. (In fact, probably too many things happen in this one
 * class).
 * @version $Revision: 1.1 $, $Date: 2002/09/13 08:13:50 $
 * @author billhorsman
 * @author $Author: billhorsman $ (current maintainer)
 */
class ConnectionPool implements ConnectionPoolStatisticsIF {
    /** TODO try to avoid expiring all connections at once! */

    private static final String[] statusDescriptions = {"NULL", "AVAILABLE", "ACTIVE", "OFFLINE"};

    private static final String MSG_MAX_CONNECTION_COUNT = "Couldn't get connection because we are at maximum connection count and there are none available";

    /** This is the pool itself */
    private Vector proxyConnections = new Vector();

    /** This allows us to have a unique ID for each connection */
    private long nextConnectionId = 0;

    /** This is the "round robin" that makes sure we use all the connections */
    private int nextAvailableConnection = 0;

    /**
     * This is usually the same as poolableConnections.size() but it sometimes higher.  It is
     * alwasy right, since a connection exists before it is added to the pool
     */
    private int connectionCount = 0;

    private long connectionsServedCount = 0;

    private long connectionsRefusedCount = 0;

    /**
     * This is only incremented when a connection is connected.  In other words, connectionCount is
     * incremented when you start to make the connectiona and connectedConnectionCount is incremented
     * after you get it.  The difference between the two gives you an indication of how  many connections
     * are being made right now.
     */
    private int connectedConnectionCount = 0;

    /** This keeps a count of how many connections there are in each state */
    private int[] connectionCountByState = new int[4];

    /** Should be useful during finalize to clean up nicely but not sure if this ever gets called */
    private boolean connectionPoolUp = false;

    private ConnectionPoolDefinition definition;

    private Thread houseKeepingThread;

    private Prototyper prototypingThread;

    private DecimalFormat countFormat = new DecimalFormat("00");

    private DecimalFormat idFormat = new DecimalFormat("0000");

    private DecimalFormat bigCountFormat = new DecimalFormat("###,000,000");

    private ConnectionListenerIF connectionListener;

    private StateListenerIF stateListener;

    private boolean lastCreateWasSuccessful = true;

    private long timeMillisOfLastRefusal = 0;

    private int upState;

    private int recentlyStartedActiveConnectionCount;

    protected ConnectionPool(ConnectionPoolDefinition definition, LogListenerIF logListener) {
        setDefinition(definition);
        connectionPoolUp = true;
        Logger.registerLogListener(logListener, definition.getName());
    }

    /** Starts up house keeping and prototyper threads. */
    protected void start() {
        info("Establishing ConnectionPool " + definition.getName());

        houseKeepingThread = new Thread(new HouseKeeper());
        houseKeepingThread.setDaemon(true);
        houseKeepingThread.setName("HouseKeeper");
        houseKeepingThread.start();

        prototypingThread = new Prototyper();
        prototypingThread.setDaemon(true);
        prototypingThread.setName("Prototyper");
        prototypingThread.start();
    }

    /**
     * Get a connection from the pool.  If none are available or there was an Exception
     * then an exception is thrown and something written to the log
     */
    protected Connection getConnection() throws SQLException {
        return getConnection(null);
    }

    /**
     * Get a connection from the pool.  If none are available or there was an Exception
     * then an exception is thrown and something written to the log
     */
    protected Connection getConnection(String requester) throws SQLException {

        if (requester == null) {
            requester = Thread.currentThread().getName();
        }

        /* If we're busy, we need to return as quickly as possible. */

        if (connectionCount >= getDefinition().getMaximumConnectionCount() && getAvailableConnectionCount() == 0) {
            connectionsRefusedCount++;
            info(displayStatistics() + " - " + MSG_MAX_CONNECTION_COUNT);
            timeMillisOfLastRefusal = System.currentTimeMillis();
            calculateUpState();
            throwSQLException(MSG_MAX_CONNECTION_COUNT);
        }

        if (prototypingThread != null) {
            // log(OK + displayStatistics() + " - prototypingThread.notify()");
            prototypingThread.wake();
        }
        else {
            prototypingThread = new Prototyper();
            prototypingThread.setDaemon(true);
            prototypingThread.start();
        }
        ProxyConnection proxyConnection = null;
        try {
            if (connectionCount - connectedConnectionCount > getDefinition().getMaximumNewConnections()) {
                throwSQLException("Already making " + (connectionCount - connectedConnectionCount) +
                    " connections - trying to avoid overload");
            }
            else {
                // We need to look at all the connections, but we don't want to keep looping round forever
                for (int connectionsTried = 0; connectionsTried < proxyConnections.size(); connectionsTried++) {
                    // By doing this in a try/catch we avoid needing to synch on the size().  We need to do be
                    // able to cope with connections being removed whilst we are going round this loop
                    try {
                        proxyConnection = (ProxyConnection) proxyConnections.elementAt(nextAvailableConnection);
                    }
                    catch (ArrayIndexOutOfBoundsException e) {
                        nextAvailableConnection = 0;
                        proxyConnection = (ProxyConnection) proxyConnections.elementAt(nextAvailableConnection);
                    }
                    // setActive() returns false if the ProxyConnection wasn't available.  You
                    // can't set it active twice (at least, not without making it available again
                    // in between)
                    if (proxyConnection != null && proxyConnection.fromAvailableToActive()) {
                        nextAvailableConnection++;
                        break;
                    }
                    else {
                        proxyConnection = null;
                    }
                    nextAvailableConnection++;
                }
                // Did we get one?
                if (proxyConnection == null) {
                    try {
                        // No!  Let's see if we can create one
                        proxyConnection = createPoolableConnection(ProxyConnection.STATUS_ACTIVE);
                        if (proxyConnection != null) {
                            addPoolableConnection(proxyConnection);
                            if (isDebugEnabled())
                                debug(displayStatistics() + " - #" +
                                    idFormat.format(proxyConnection.getId()) + " on demand");
                        }
                        else {
                            if (isDebugEnabled()) debug("createPoolableConnection returned null");
                        }
                    }
                    catch (Exception e) {
                        error(e);
                        throwSQLException(e.toString());
                    }
                }

            }
        }
        catch (SQLException e) {
            if (isDebugEnabled()) debug(e);
            calculateUpState();
            throw e;
        }
        catch (Throwable t) {
            error(t);
            if (isDebugEnabled()) debug(t);
            calculateUpState();
            throwSQLException(t.toString());
        }
        finally {
            if (proxyConnection != null) {
                connectionsServedCount++;
                proxyConnection.setRequester(requester);
                calculateUpState();
                return proxyConnection;
            }
            else {
                connectionsRefusedCount++;
                timeMillisOfLastRefusal = System.currentTimeMillis();
                calculateUpState();
            }
        }

        if (proxyConnection == null) {
            throwSQLException("Unknown reason for not getting connection. Sorry.");
        }

        return proxyConnection;
    }

    private void throwSQLException(String message) throws SQLException {
        throw new SQLException(message + " [stats: " + displayStatistics() + "]");
    }

    private ProxyConnection createPoolableConnection(int state) throws ClassNotFoundException, SQLException {
        // Synch here rather than whole method because if is the new ProxyConnection() after
        // the synch that will take the time
        // It would be simpler to synch the whole thing from when we create the connection to adding it to the
        // pool but this would cause a bottleneck because only one connection could be created at a time.  It
        // complicates things doing it this way but I think it's worth it.
        synchronized (this) {
            // Check that we are allowed to make another connection
            if (connectionCount >= getDefinition().getMaximumConnectionCount()) {
                throwSQLException("ConnectionCount is " + connectionCount + ". Maximum connection count of " + getDefinition().getMaximumConnectionCount() + " cannot be exceeded.");
            }
            connectionCount++;
        }

        ProxyConnection proxyConnection = null;

        try {
            proxyConnection = new ProxyConnection(getNextId(), getDefinition());

            onBirth(proxyConnection);
            switch (state) {
                case ProxyConnection.STATUS_ACTIVE:
                    proxyConnection.fromOfflineToActive();
                    break;

                case ProxyConnection.STATUS_AVAILABLE:
                    proxyConnection.fromOfflineToAvailable();
                    break;

                default:
/* Not quite sure what we should do here. oh well, leave it offline*/
                    error(displayStatistics() + " - Didn't expect to set new connection to state " + state);
            }

            if (proxyConnection.getStatus() != state) {
                throwSQLException("Unable to set connection #" + proxyConnection.getId() + " to " +
                    statusDescriptions[state]);

            }
            else {
                if (isDebugEnabled() && getDefinition().getDebugLevel() == ConnectionPoolDefinitionIF.DEBUG_LEVEL_LOUD) debug(displayStatistics() + " - Connection #" + proxyConnection.getId() + " is now " + proxyConnection.getStatus());
            }
        }
        catch (SQLException e) {
            // error(displayStatistics() + " - Couldn't initialise connection #" + proxyConnection.getId() + ": " + e);
            throw e;
        }
        catch (ClassNotFoundException e) {
            if (isDebugEnabled()) debug(e);
            throw e;
        }
        catch (RuntimeException e) {
            if (isDebugEnabled()) debug(e);
            throw e;
        }
        catch (Throwable t) {
            if (isDebugEnabled()) debug(t);
        }
        finally {

            lastCreateWasSuccessful = (proxyConnection != null);
            calculateUpState();

            if (!lastCreateWasSuccessful) {
                // If there has been an exception then we won't be using this one and
                // we need to decrement the counter
                connectionCount--;

            }

        }

        return proxyConnection;
    }

    /** Add a ProxyConnection to the pool */
    private void addPoolableConnection(ProxyConnection ProxyConnection) {
        proxyConnections.addElement(ProxyConnection);
    }

    /**
     * It's nice to identify each Connection individually rather than just use their index
     * within the pool (which changes all the time)
     */
    private synchronized long getNextId() {
        return nextConnectionId++;
    }

    /**
     * When you have finished with a Connection you should put it back here.  That will make it available to others.
     * Unless it's due for expiry, in which case it will... expire
     */
    protected void putConnection(ProxyConnection proxyConnection) {
        try {
            // It's possible that this connection is due for expiry
            if (proxyConnection.isMarkedForExpiry()) {
                if (proxyConnection.fromActiveToNull()) {
                    expirePoolableConnection(proxyConnection, REQUEST_EXPIRY);
                }
            }
            else {
                // Let's make it available for someone else
                proxyConnection.fromActiveToAvailable();
            }
        }
            // This is probably due to getPoolableConnection returning a null.
        finally {
            calculateUpState();
        }
    }

    /** This means that there's something wrong the connection and it's probably best if no one uses it again. */
    protected void throwConnection(Connection connection) {
        try {
            ProxyConnection proxyConnection = (ProxyConnection) connection;

            if (proxyConnection.fromActiveToNull()) {
                expirePoolableConnection(proxyConnection, REQUEST_EXPIRY);
            }
        }
            // This is probably due to getPoolableConnection returning a null.
        catch (ClassCastException e) {
            error("Tried to throwConnection() a connection that wasn't a ProxyConnection");
        }
        finally {
            calculateUpState();
        }
    }

    /** Get a ProxyConnection by index */
    private ProxyConnection getProxyConnection(int i) {
        return (ProxyConnection) proxyConnections.elementAt(i);
    }

    protected void removePoolableConnection(ProxyConnection proxyConnection, String reason, boolean forceExpiry) {
        // Just check that it is null
        if (forceExpiry || proxyConnection.isNull()) {

            proxyConnection.setStatus(ProxyConnection.STATUS_NULL);

            /* Run some code everytime we destroy a connection */

            try {
                onDeath(proxyConnection);
            }
            catch (SQLException e) {
                error(e);
            }

            // The reallyClose() method also decrements the connectionCount.
            try {
                proxyConnection.reallyClose();
            }
            catch (SQLException e) {
                error(e);
            }

            proxyConnections.removeElement(proxyConnection);

            if (isDebugEnabled())
                debug(displayStatistics() + " - #" + idFormat.format(proxyConnection.getId()) +
                    " removed because " + reason + ".");
            // if the prototyper is suspended then wake it up
            if (prototypingThread != null) {
                // log(OK + displayStatistics() + " - prototypingThread.wake()");
                prototypingThread.wake();
            }
            else {
                prototypingThread = new Prototyper();
                prototypingThread.setDaemon(true);
                prototypingThread.start();
            }
        }
        else {
            error(displayStatistics() + " - #" + idFormat.format(proxyConnection.getId()) +
                " was not removed because isNull() was false.");
        }
    }

    private void expirePoolableConnection(ProxyConnection ProxyConnection, boolean forceExpiry) {
        removePoolableConnection(ProxyConnection, "age is " + ProxyConnection.getAge() + "ms", forceExpiry);
        calculateUpState();
    }

    /**
     * Call this to shutdown gracefully.
     *  @param delay how long to wait for connections to become free before forcing them to close anyway
     */
    protected void finalize(int delay, String finalizerName) throws Throwable {

        /* This will stop us giving out any more connections and may
        cause some of the threads to die. */

        if (connectionPoolUp == true) {

            connectionPoolUp = false;

            if (delay > 0) {
                info("Closing down connection pool - waiting for " + delay +
                    " milliseconds for everything to stop by " + finalizerName);
            }
            else {
                info("Closing down connection pool immediately by " + finalizerName);
            }

            /* Interrupt the threads (in case they're sleeping) */

            try {
                try {
                    wakeThread(houseKeepingThread);
                }
                catch (Exception e) {
                    error("Can't wake houseKeepingThread", e);
                }

                try {
                    wakeThread(prototypingThread);
                }
                catch (Exception e) {
                    error("Can't wake prototypingThread", e);
                }

                /* Patience, patience. */

                try {
                    Thread.sleep(delay);
                }
                catch (InterruptedException e) {
                    // Oh well
                }

                // Silently close all connections
                for (int i = proxyConnections.size() - 1; i >= 0; i--) {
                    try {
                        if (isDebugEnabled()) debug("Closing connection #" + getProxyConnection(i).getId());
                        getProxyConnection(i).close();
                    }
                    catch (Throwable t) {
                        // Ignore
                    }
                }
            }
            finally {
                if (isDebugEnabled()) debug("Connection pool has been finalized (stopped) by " + finalizerName);
                super.finalize();
            }
        }
        else {
            if (isDebugEnabled()) debug("Ignoring duplicate attempt to finalize connection pool by " + finalizerName);
        }
    }

    // In theory, this gets called by the JVM.  Never actually does though.
    protected void finalize() throws Throwable {
        /* No delay! */

        finalize(0, "JVM");
    }

    private void wakeThread(Thread thread) {
        thread.interrupt();
    }

    public int getAvailableConnectionCount() {
        return connectionCountByState[ProxyConnection.STATUS_AVAILABLE];
    }

    public int getActiveConnectionCount() {
        return connectionCountByState[ProxyConnection.STATUS_ACTIVE];
    }

    public int getOfflineConnectionCount() {
        return connectionCountByState[ProxyConnection.STATUS_OFFLINE];
    }

    protected String displayStatistics() {
        StringBuffer statistics = new StringBuffer();
        statistics.append(bigCountFormat.format(getConnectionsServedCount()));

        statistics.append(" (");
        statistics.append(countFormat.format(getActiveConnectionCount()));
        statistics.append("/");
        statistics.append(countFormat.format(getAvailableConnectionCount() + getActiveConnectionCount()));
        statistics.append(")");

        if (getConnectionsRefusedCount() > 0) {
            statistics.append(" -");
            statistics.append(bigCountFormat.format(getConnectionsRefusedCount()));
        }

        if (getOfflineConnectionCount() > 0) {
            statistics.append(" [");
            statistics.append(bigCountFormat.format(getOfflineConnectionCount()));
            statistics.append("]");
        }

        if (getDefinition().getDebugLevel() == ConnectionPoolDefinitionIF.DEBUG_LEVEL_LOUD) {
            statistics.append(", cc=");
            statistics.append(connectionCount);
            statistics.append(", ccc=");
            statistics.append(connectedConnectionCount);
        }

        return statistics.toString();
    }

    class HouseKeeper implements Runnable {
        /** Housekeeping thread to look after all the connections */
        public void run() {
            // We just use this to see how the Connection is
            Statement testStatement = null;
            while (connectionPoolUp) {
                try {

                    try {
                        Thread.sleep(getDefinition().getHouseKeepingSleepTime());
                    }
                    catch (InterruptedException e) {
                        return;
                    }

                    if (getDefinition().getDebugLevel() > getDefinition().DEBUG_LEVEL_QUIET) {
                        if (isDebugEnabled()) debug(displayStatistics() + " - House keeping start");
                    }

                    // Right, now we know we're the right thread then we can carry on house keeping
                    ProxyConnection proxyConnection = null;

                    int recentlyStartedActiveConnectionCountTemp = 0;

                    // Loop through backwards so that we can cope with connections being removed
                    // as we go along.  If an element is removed (and it is was one haven't reached
                    // yet) the only risk is that we test the same connection twice (which doesn't
                    // matter at all).  If an element is added then it is added to the end and we
                    // will miss it on this house keeping run (but we'll get it next time).
                    for (int i = proxyConnections.size() - 1; i >= 0; i--) {
                        proxyConnection = getProxyConnection(i);
                        // First lets check whether the connection still works. We should only validate
                        // connections that are not is use!  SetOffline only succeeds if the connection
                        // is available.
                        if (proxyConnection.fromAvailableToOffline()) {
                            try {
                                testStatement = proxyConnection.createStatement();

                                // Some DBs return an object even if DB is shut down
                                if (proxyConnection.isReallyClosed()) {
                                    proxyConnection.fromOfflineToNull();
                                    removePoolableConnection(proxyConnection, "it appears to be closed", FORCE_EXPIRY);
                                } // END if (ProxyConnection.getConnection().isReallyClosed())

                                String sql = getDefinition().getHouseKeepingTestSql();
                                if (sql != null && sql.length() > 0) {
                                    // A Test Statement has been provided. Execute it!
                                    if (isDebugEnabled() && getDefinition().getDebugLevel() == ConnectionPoolDefinitionIF.DEBUG_LEVEL_LOUD) debug("Testing connection " + proxyConnection.getId() + " ...");
                                    boolean testResult = testStatement.execute(sql);
                                    if (isDebugEnabled() && getDefinition().getDebugLevel() == ConnectionPoolDefinitionIF.DEBUG_LEVEL_LOUD) debug("Connection " + proxyConnection.getId() + (testResult ? " OK" : " returned with false"));
                                } // END if (sql != null && sql.length() > 0)

                                proxyConnection.fromOfflineToAvailable();
                            }
                            catch (SQLException e) {
                                // There is a problem with this connection.  Let's remove it!
                                proxyConnection.fromOfflineToNull();
                                removePoolableConnection(proxyConnection, "it has problems: " + e, REQUEST_EXPIRY);
                            }
                            finally {
                                try {
                                    testStatement.close();
                                }
                                catch (Throwable t) {
                                    // Never mind.
                                }
                            }
                        } // END if (poolableConnection.setOffline())
                        // Now to check whether the connection is due for expiry
                        if (proxyConnection.getAge() > getDefinition().getMaximumConnectionLifetime()) {
                            // Check whether we can make it offline
                            if (proxyConnection.fromAvailableToOffline()) {
                                if (proxyConnection.fromOfflineToNull()) {
                                    // It is.  Expire it now .
                                    expirePoolableConnection(proxyConnection, REQUEST_EXPIRY);
                                }
                            }
                            else {
                                // Oh no, it's in use.  Never mind, we'll mark it for expiry
                                // next time it is available.  This will happen in the
                                // putConnection() method.
                                proxyConnection.markForExpiry();
                                if (isDebugEnabled())
                                    debug(displayStatistics() + " - #" + idFormat.format(proxyConnection.getId()) +
                                        " marked for expiry.");
                            } // END if (poolableConnection.setOffline())
                        } // END if (poolableConnection.getAge() > maximumConnectionLifetime)

                        // Now let's see if this connection has been active for a
                        // suspiciously long time.
                        if (proxyConnection.isActive()) {

                            long activeTime = System.currentTimeMillis() - proxyConnection.getTimeLastStartActive();

                            if (activeTime < getDefinition().getRecentlyStartedThreshold()) {

                                // This connection hasn't been active for all that long
                                // after all. And as long as we have at least one
                                // connection that is "actively active" then we don't
                                // consider the pool to be down.
                                recentlyStartedActiveConnectionCountTemp++;
                            }

                            if (activeTime > getDefinition().getMaximumActiveTime()) {

                                // This connection has been active for way too long. We're
                                // going to kill it :)
                                removePoolableConnection(proxyConnection,
                                    "it has been active for " + activeTime +
                                    " milliseconds", FORCE_EXPIRY);

                            }
                        }

/* TODO @P S @R BH @description What should we do with very old active connections? */

                    } // END for (int i = poolableConnections.size() - 1; i >= 0; i--)

                    setRecentlyStartedActiveConnectionCount(recentlyStartedActiveConnectionCountTemp);

                    if (prototypingThread != null) {
                        // log(OK + displayStatistics() + " - prototypingThread.notify()");
                        prototypingThread.wake();
                    }
                    else {
                        prototypingThread = new Prototyper();
                        prototypingThread.setDaemon(true);
                        prototypingThread.start();
                    }

                    calculateUpState();
                }
                catch (RuntimeException e) {
                    // We don't want the housekeeping thread to fall over!
                    error("Housekeeping error :", e);
                }
                finally {
                    if (getDefinition().getDebugLevel() > getDefinition().DEBUG_LEVEL_QUIET) {
                        if (isDebugEnabled()) debug(displayStatistics() + " - House keeping stop");
                    }
                }
            } // END while (connectionPoolUp)
        }
    }

    /**
     * If you start this up as a Thread it will try and make sure there are some
     * free connections available in case you need them.  Because we are now a bit more rigorous in
     * destroying unwanted connections we want to make sure that we don't create them on demand to
     * often.  Creating on demand makes the user wait (and we don't want that do we?)
     */
    class Prototyper extends Thread {

        public void run() {

            while (connectionPoolUp) {

                if (getDefinition().getDebugLevel() > getDefinition().DEBUG_LEVEL_QUIET) {
                    if (isDebugEnabled()) debug(displayStatistics() + " - Prototyping start");
                }

                boolean keepAtIt = true;
                while (connectionPoolUp && keepAtIt && (connectionCount < getDefinition().getMinimumConnectionCount() ||
                    getAvailableConnectionCount() < getDefinition().getPrototypeCount()) && connectionCount < getDefinition().getMaximumConnectionCount()) {
                    try {
                        ProxyConnection poolableConnection = createPoolableConnection(ProxyConnection.STATUS_AVAILABLE);
                        if (poolableConnection == null) {
                            // If we failed on one then might as well give up.  Probably
                            // because we've reached the maximum anyway.
                            break;
                        }
                        else {
                            addPoolableConnection(poolableConnection);
                            if (isDebugEnabled())
                                debug(displayStatistics() + " - #" +
                                    idFormat.format(poolableConnection.getId()) + " prototype");
                        }
                    }
                    catch (Exception e) {
                        error("Prototype", e);
                        // If there's been an exception, perhaps we should stop
                        // prototyping for a while.  Otherwise if the database
                        // has problems we end up trying the connection every 2ms
                        // or so and then the log grows pretty fast.
                        keepAtIt = false;
                        // Don't wory, we'll start again the next time the
                        // housekeeping thread runs.
                    }
                }

                if (getDefinition().getDebugLevel() > getDefinition().DEBUG_LEVEL_QUIET) {
                    if (isDebugEnabled()) debug(displayStatistics() + " - Prototyping stop");
                }

                try {
                    synchronized (this) {
                        this.wait();
                    }
                }
                catch (InterruptedException e) {
                    //
                }
            }
        }

        protected synchronized void wake() {
            this.notify();
        }
    }

    protected void expireAllConnections(boolean merciful) {
        for (int i = proxyConnections.size() - 1; i >= 0; i--) {
            expireConnectionAsSoonAsPossible((ProxyConnection) proxyConnections.elementAt(i), merciful);
        }
    }

    protected void expireConnectionAsSoonAsPossible(ProxyConnection poolableConnection, boolean merciful) {
        if (poolableConnection.fromAvailableToOffline()) {
            if (poolableConnection.fromOfflineToNull()) {
                // It is.  Expire it now .
                expirePoolableConnection(poolableConnection, REQUEST_EXPIRY);
            }
        }
        else {
            // Oh no, it's in use.

            if (merciful) {
                //Never mind, we'll mark it for expiry
                // next time it is available.  This will happen in the
                // putConnection() method.
                poolableConnection.markForExpiry();
                if (isDebugEnabled()) debug(displayStatistics() + " - #" + idFormat.format(poolableConnection.getId()) + " marked for expiry.");
            }
            else {
                // So? Kill, kill, kill

                // We have to make sure it's null first.
                expirePoolableConnection(poolableConnection, FORCE_EXPIRY);
            }

        } // END if (poolableConnection.setOffline())
    }

    protected void registerRemovedConnection(int status) {
        if (isDebugEnabled()) debug("Decrementing connectionCount, connectedConnectionCount and connectionCountByState[" + status + "]");
        connectionCount--;
        connectedConnectionCount--;
        connectionCountByState[status]--;
    }

    protected void changeStatus(int oldStatus, int newStatus) {
        connectionCountByState[oldStatus]--;
        connectionCountByState[newStatus]++;
    }

    protected void incrementConnectedConnectionCount() {
        connectedConnectionCount++;
    }

    public long getConnectionsServedCount() {
        return connectionsServedCount;
    }

    public long getConnectionsRefusedCount() {
        return connectionsRefusedCount;
    }

    protected ConnectionPoolDefinition getDefinition() {
        return definition;
    }

    /**
     * Changes both the way that any new connections will be made, and the behaviour of the pool. Consider
     * calling expireAllConnections() if you're in a hurry.
     */
    protected synchronized void setDefinition(ConnectionPoolDefinition definition) {
        this.definition = definition;

        try {
            Class.forName(definition.getDriver());
        }
        catch (ClassNotFoundException e) {
            error(e);
        }

    }

    public void setStateListener(StateListenerIF stateListener) {
        this.stateListener = stateListener;
    }

    public void setConnectionListener(ConnectionListenerIF connectionListener) {
        this.connectionListener = connectionListener;
    }

    /** Call the onBirth() method on each StateListenerIF . */
    private void onBirth(ProxyConnection proxyConnection) throws SQLException {
        if (connectionListener != null) {
            connectionListener.onBirth(proxyConnection);
        }
    }

    /** Call the onDeath() method on each StateListenerIF . */
    protected void onDeath(ProxyConnection proxyConnection) throws SQLException {
        if (connectionListener != null) {
            connectionListener.onDeath(proxyConnection);
        }
    }

    /** Call the onBirth() method on each StateListenerIF . */
    protected void cleanupClob(ProxyConnection proxyConnection, Clob clob) throws SQLException {
        if (connectionListener != null) {
            connectionListener.cleanupClob(proxyConnection, clob);
        }
    }

    public String toString() {
        return getDefinition().toString();
    }

    public int getUpState() {
        return upState;
    }

    private void calculateUpState() {

        try {

            int calculatedUpState = StateListenerIF.STATE_QUIET;

/* We're up if the last time we tried to make a connection it
             * was successful
             */

/* I've changed the way we do this. Just because we failed to create
             * a connection doesn't mean we're down. As long as we have some
             * available connections, or the active ones we have aren't locked
             * up then we should be able to struggle on. The last thing we want
             * to do is say we're down when we're not!
             */

            // if (this.lastCreateWasSuccessful) {
            if (getAvailableConnectionCount() > 0 || getRecentlyStartedActiveConnectionCount() > 0) {

/* Defintion of overloaded is that we refused a connection
                 * (because we were too busy) within the last minute.
                 */

                if (this.timeMillisOfLastRefusal > (System.currentTimeMillis() -
                    getDefinition().getOverloadWithoutRefusalLifetime())) {
                    calculatedUpState = StateListenerIF.STATE_OVERLOADED;
                }

/* Are we doing anything at all?
                 */

                else if (getActiveConnectionCount() > 0) {
                    calculatedUpState = StateListenerIF.STATE_BUSY;
                }

            }
            else {
                calculatedUpState = StateListenerIF.STATE_DOWN;
            }

            setUpState(calculatedUpState);

        }
        catch (Exception e) {
            error(e);
        }
    }

    public void setUpState(int upState) {

        if (this.upState != upState) {

            if (stateListener != null) {
                stateListener.upStateChanged(upState);
            }

            this.upState = upState;

        }
    }

    public Collection getConnectionInfos() {
        return proxyConnections;
    }

    public int getRecentlyStartedActiveConnectionCount() {
        return recentlyStartedActiveConnectionCount;
    }

    public void setRecentlyStartedActiveConnectionCount(int recentlyStartedActiveConnectionCount) {
        this.recentlyStartedActiveConnectionCount = recentlyStartedActiveConnectionCount;
    }

    /**
     * Manually expire a connection.
     * @param id the id of the connection to kill
     * @param forceExpiry use true to expire even if it is in use
     * @return true if the connection was found and expired, else false
     */
    public boolean expireConnection(long id, boolean forceExpiry) {
        boolean success = false;
        ProxyConnection proxyConnection = null;

        // We need to look at all the connections, but we don't want to keep looping round forever
        for (int connectionsTried = 0; connectionsTried < proxyConnections.size(); connectionsTried++) {
            // By doing this in a try/catch we avoid needing to synch on the size().  We need to do be
            // able to cope with connections being removed whilst we are going round this loop
            try {
                proxyConnection = (ProxyConnection) proxyConnections.elementAt(nextAvailableConnection);
            }
            catch (ArrayIndexOutOfBoundsException e) {
                nextAvailableConnection = 0;
                proxyConnection = (ProxyConnection) proxyConnections.elementAt(nextAvailableConnection);
            }

            if (proxyConnection.getId() == id) {
                // This is the one
                proxyConnection.fromAvailableToOffline();
                proxyConnection.fromOfflineToNull();
                removePoolableConnection(proxyConnection, "it was manually killed", forceExpiry);
                calculateUpState();
                success = true;
                break;
            }

            nextAvailableConnection++;
        }

        if (!success) {
            if (isDebugEnabled())
                debug(displayStatistics() + " - couldn't find " + idFormat.format(proxyConnection.getId()) +
                    " and I've just been asked to expire it");
        }

        return success;
    }

    private static final boolean FORCE_EXPIRY = true;

    private static final boolean REQUEST_EXPIRY = false;

    protected void debug(Object o) {
        Logger.debug(o, getDefinition().getName());
    }

    protected boolean isDebugEnabled() {
        return Logger.isDebugEnabled(getDefinition().getName());
    }

    protected void info(Object o) {
        Logger.info(o, getDefinition().getName());
    }

    protected boolean isInfoEnabled() {
        return Logger.isInfoEnabled(getDefinition().getName());
    }

    protected void error(Object o) {
        Logger.error(o, getDefinition().getName());
    }

    protected void error(Object o, Throwable t) {
        Logger.error(o, t, getDefinition().getName());
    }

}

/*
 Revision history:
 $Log: ConnectionPool.java,v $
 Revision 1.1  2002/09/13 08:13:50  billhorsman
 Initial revision

 Revision 1.10  2002/07/04 09:05:36  billhorsman
 Fixes

 Revision 1.9  2002/07/02 11:19:08  billhorsman
 layout code and imports

 Revision 1.8  2002/07/02 11:14:26  billhorsman
 added test (andbug fixes) for FileLogger

 Revision 1.7  2002/07/02 08:39:55  billhorsman
 getConnectionInfos now returns a Collection instead of an array. displayStatistics is now available to ProxoolFacade. Prototyper no longer tries to make connections when maximum is reached (stopping unnecessary log messages). bug fix.

 Revision 1.6  2002/06/28 11:19:47  billhorsman
 improved doc

*/