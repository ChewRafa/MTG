package server;

import server.flags.*;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.logging.Level;
import java.util.logging.Logger;
import mtg.Debug;
import mtg.Deck;
import mtg.InvalidDeckException;
import mtg.Main;
import mtg.Utilities;
import mtg.Zone;

/**
 * @author Jaroslaw Pawlak
 */
public class Server extends Thread {
    /**
     * Main server thread is running and awaiting connections.
     */
    public static final int RUNNING = 0;
    /**
     * Players have connected, game has been initialised, main server thread
     * id dead. Some of SLTs are alive. 
     */
    public static final int PLAYERS_CONNECTED = 1;
    /**
     * Game has been initialised, but all players have already left it. Main
     * server thread and all SLTs are dead.
     */
    public static final int DEAD = 2;
    
    private static int port;

    private static ServerSocket ss;

    private static ServerListeningThread[] serverListeningThreads;
    private static Socket[] socket;
    private static ServerSocket[] fileSocket;
    private static ObjectOutputStream[] oos;
    private static ObjectInputStream[] ois;

    private static Deck[] decks;
    private static String[] names;

    static boolean[] ready;

    static Game game;

    private static Server serverMainThread;

    private Server() {
        super("Server Main Thread");
    }

    /**
     * Waits for connection of <code>players</code> number of players. Downloads
     * their decks and exchange with other players missing cards.
     * @param port port to be used for communication. For exchanging cards
     * there will be used ports between <code>port + 1</code> and
     * <code>port + players</code>, both inclusive.
     * @param players number of players
     * @throws IOException if an I/O error occurs when opening the socket.
     * See {@link java.net.ServerSocket#ServerSocket(int)}
     */
    public static void start(int port, int players) throws IOException {
        Server.port = port;

        serverListeningThreads = new ServerListeningThread[players];
        socket = new Socket[players];
        fileSocket = new ServerSocket[players];
        oos = new ObjectOutputStream[players];
        ois = new ObjectInputStream[players];
        
        decks = new Deck[players];
        names = new String[players];

        ready = new boolean[players]; //TODO shouldn't it be synchronized?

        ss = new ServerSocket(port);

        serverMainThread = new Server();
        serverMainThread.start();
    }

    @Override
    public void run() {
        for (int i = 0; i < ready.length; i++) {
            Debug.p("Server: waiting for player " + i + "/" + ready.length);
            ready[i] = false;
            CheckDeck newdeck = null;
            
            try {
                fileSocket[i] = new ServerSocket(port + i + 1);
                socket[i] = ss.accept();
                ois[i] = new ObjectInputStream(socket[i].getInputStream());
                oos[i] = new ObjectOutputStream(socket[i].getOutputStream());
                oos[i].flush();

                // exchange basic info
                newdeck = (CheckDeck) ois[i].readObject();
                try {
                    Deck.check(newdeck.deck);
                } catch (InvalidDeckException ex) {
                    oos[i].writeObject(ex);
                    oos[i].flush();
                    throw ex;
                }
                newdeck.owner = checkName(Utilities.checkName(newdeck.owner));
                names[i] = newdeck.owner;
                decks[i] = newdeck.deck;
                oos[i].writeObject(names[i]);
                oos[i].flush();
                oos[i].writeInt(port + i + 1);
                oos[i].flush();
                oos[i].writeInt(ready.length);
                oos[i].flush();

                // check new deck and download missing cards
                for (int j = 0; j < decks[i].getArraySize(); j++) {
                    if (Utilities.findPath(decks[i].getArrayNames(j)) == null) {
                        // send card request
                        send(i, new RequestCard(decks[i].getArrayNames(j)));

                        // receive file
                        try (Socket t = fileSocket[i].accept()) {
                            Utilities.receiveFile(new File(Main.CARDS_DL, 
                                    decks[i].getArrayNames(j) + ".jpg"), t);
                        }
                    }
                }
                Debug.p("Server: Missing cards downloaded");

                // start listening to the new client
                serverListeningThreads[i]
                        = new ServerListeningThread(i, ois[i], fileSocket[i]);
                serverListeningThreads[i].start();
            } catch (Exception ex) {
                if (getStatus() != RUNNING) {
                    return;
                }
//                Logger.getLogger(Server.this.getName()).log(Level.SEVERE, null, ex);
                String ip = socket[i] == null? null : socket[i].getLocalAddress() == null?
                    "not received" : "" + socket[i].getLocalAddress();
                String msg = "Server: Error while dealing with player " + i + ": "
                        + "IP = " + ip + ", name = " + names[i]
                        + ", exception = " + ex;
                Debug.p(msg, Debug.W);
                try {
                    fileSocket[i].close();
                } catch (Exception ex2) {}
                i--;
                continue;
            }


            // all clients check all decks
            for (int prev = 0; prev < i; prev++) {
                ready[prev] = false;
                // send new deck to already connected clients
                send(prev, newdeck);
                // send already connected clients' decks to the new client
                send(i, new CheckDeck(names[prev], decks[prev]));
                if (!serverListeningThreads[prev].isAlive()) { //INIT KILL
                    send(i, new Disconnect(prev, true));
                }
            }

            send(i, newdeck);
        }
        Debug.p("Server: Game initialisation finished", Debug.I);

        boolean allReady = false;
        while (!allReady) {
            for (int i = 0; i < ready.length; i++) {
                if (!ready[i]) {
                    break;
                }
            }
            allReady = true;
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ex) {}
        }

        game = new Game(decks);
        CardsList x = new CardsList(game.getAllCardsList());
        for (int i = 0; i < ready.length; i++) {
            send(i, x);
        }

        for (int p = 0; p < ready.length; p++) {
            if (serverListeningThreads[p].isAlive()) {
                game.libraryShuffle(p);
                sendToAll(new Shuffle(p));
                for (int c = 0; c < 7; c++) {
                    Card card = game.libraryDraw(p);
                    if (card != null) {
                        sendToAllInvisible(new MoveCard(Zone.TOP_LIBRARY,
                                Zone.HAND, p, card.ID));
                    }
                }
            } else { //INIT KILL - player disconnected before game started
                game.kill(p);
            }
        }

        Debug.p("Server: Server main thread terminates");
    }

    public synchronized static int getStatus() {
        if (serverMainThread == null) {
            return DEAD;
        } else if (serverMainThread.isAlive()
                && !serverMainThread.isInterrupted()) {
            return RUNNING;
        } else if (serverListeningThreads == null) {
            return DEAD; //when server has been closed
        } else {
            for (ServerListeningThread slt : serverListeningThreads) {
                if (slt != null && slt.isAlive() && !slt.isInterrupted()) {
                    return PLAYERS_CONNECTED;
                }
            }
            return DEAD;
        }
    }

    /**
     * Sends an action to specified player.
     * @param player player to be sent to
     * @param object action to be sent
     */
    static void send(int player, Action object) {
        if (oos[player] != null) {
            try {
                oos[player].writeObject(object);
                oos[player].flush();
            } catch (IOException ex) {
                Debug.p("Server: Error while sending " + object + " to player "
                        + player + ": " + ex, Debug.E);
            }
        }
    }

    /**
     * Sends an action to all players.
     * @param object action to be sent
     */
    static void sendToAll(Action object) {
        for (int i = 0; i < ready.length; i++) {
            send(i, object);
        }
    }
    
    /**
     * Sends an action to all players except specified player.
     * @param player player to be ignored
     * @param object action to be sent
     */
    static void sendToAllExcept(int player, Action object) {
        for (int i = 0; i < ready.length; i++) {
            if (i != player) {
                send(i, object);
            }
        }
    }

    /**
     * Sends a MoveCard action to all players but only to a requestor player
     * is sent a card's ID. For example, if there are four players and player 2
     * draws a card, players 0, 1 and 3 receives MoveCard object but with
     * no card ID, while player 2 receives a full object with a proper ID.
     * @param mc object to be sent
     */
    static void sendToAllInvisible(MoveCard mc) {
        String id = mc.cardID;
        mc.cardID = null;
        for (int i = 0; i < ready.length; i++) {
            if (i == mc.requestor) {
                mc.cardID = id;
                Server.send(i, mc);
                mc.cardID = null;
            } else {
                Server.send(i, mc);
            }
        }
    }
    
    /**
     * Sends a Restart action to all players but only to a requestor player
     * is sent cards' IDs.
     * @param r object to be sent
     */
    static void sendToAllInvisible(Restart r) {
        String[] ids = r.IDs;
        r.IDs = null;
        for (int i = 0; i < ready.length; i++) {
            if (i == r.requestor) {
                r.IDs = ids;
                Server.send(i, r);
                r.IDs = null;
            } else {
                Server.send(i, r);
            }
        }
    }

    /**
     * Sends a search Action to all the players, but only requestor player
     * receives list of cards. For other players it is only an information
     * that a player is searching a zone.
     * @param s Search action
     */
    static void sendToAllInvisible(Search s) {
        String[] cards = s.cardsIDs;
        s.cardsIDs = null;
        for (int i = 0; i < ready.length; i++) {
            if (i == s.requestor) {
                s.cardsIDs = cards;
                Server.send(i, s);
                s.cardsIDs = null;
            } else {
                Server.send(i, s);
            }
        }
    }

    /**
     * Closes all streams and sockets of given player. It should be used when
     * clients sends information about its disconnection. Game is modified
     * (player's cards are exiled) and if no one is connected
     * a server is closed.
     * @param player player
     */
    static void disconnect(int player) {
        disconnectOnly(player);
        if (game != null) {
            game.kill(player);
        }
        if (getStatus() == DEAD) { //last client disconnects
            closeServerNoOneConnected();
        }
    }
    
    /**
     * Just disconnects requested player by closing all their streams
     * and sockets and assigning null to their references. If player is already
     * disconnected, it does nothing.
     * @param player player
     */
    private static void disconnectOnly(int player) {
        if (socket[player] != null) {
            Debug.p("Server: Player " + player + " (" + names[player] + ") disconneced");
            if (serverListeningThreads[player] != null) {
                serverListeningThreads[player].interrupt();
            }
            try {
                socket[player].close();
            } catch (IOException ex) {}
            socket[player] = null;
            ois[player] = null;
            oos[player] = null;
        }
        try {
            fileSocket[player].close();
            fileSocket[player] = null;
        } catch (IOException | NullPointerException ex) {}
    }
    
    /**
     * Informs all players about server closure, disconnects all clients
     * and closes a server with no client connected to it.
     */
    public static void closeServer() {
        sendToAll(new Disconnect(true));
        for (int i = 0; i < ready.length; i++) {
            disconnectOnly(i);
        }
        closeServerNoOneConnected();
    }
    
    /**
     * Closes all sockets and streams and assigns nulls to their references.
     */
    private static void closeServerNoOneConnected() {
        serverMainThread.interrupt();
        try {
            ss.close();
        } catch (IOException ex) {}
        //let gc do the rest
        ss = null;
        serverListeningThreads = null;
        socket = null;
        fileSocket = null;
        oos = null;
        ois = null;
        decks = null;
        names = null;
        Debug.p("Server: Server closed");
    }

    private static String checkName(String name) {
        for (int i = 0; i < names.length; i++) {
            if (name.equals(names[i])) {
                return checkName(name + "-");
            }
        }
        return name;
    }
    
    static int getDeckSize(int player) {
        return decks[player].getDeckSize();
    }
}
