package server;

import server.flags.*;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import mtg.Debug;
import mtg.Deck;
import mtg.Main;
import mtg.Utilities;

/**
 * @author Jaroslaw Pawlak
 */
public class Server extends Thread {

    private static Thread serverMainThread;

    private static int port;

    private static ServerSocket ss;

    private static ServerListeningThread[] serverListeningThreads;
    private static Socket[] socket;
    private static ServerSocket[] fileSocket;
    private static ObjectOutputStream[] oos;
    private static ObjectInputStream[] ois;

    private static Deck[] deck;
    private static String[] name;

    static boolean[] ready;

    private Server() {}

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
        
        deck = new Deck[players];
        name = new String[players];

        ready = new boolean[players];

        ss = new ServerSocket(port);

        serverMainThread = new Server();
        serverMainThread.start();
    }

    @Override
    public void run() {
        for (int i = 0; i < ready.length; i++) {
            Debug.p("waiting for player " + i + "/" + ready.length);
            ready[i] = false;
            try {
                fileSocket[i] = new ServerSocket(port + i + 1);
                socket[i] = ss.accept();
                ois[i] = new ObjectInputStream(socket[i].getInputStream());
                oos[i] = new ObjectOutputStream(socket[i].getOutputStream());
                oos[i].flush();

                // exchange basic info
                CheckDeck newdeck = (CheckDeck) ois[i].readObject();
                name[i] = newdeck.owner;
                deck[i] = newdeck.deck;
                deck[i].save(new File(Main.DECKS_DL, Utilities
                        .getCurrentTimeForFile()+ " " + name[i] + ""
                        + deck[i].getName() + ".txt"));
                oos[i].writeInt(port + i + 1);
                oos[i].flush();

                // check new deck and download missing cards
                for (int j = 0; j < deck[i].getArraySize(); j++) {
                    if (Utilities.findPath(deck[i].getArrayNames(j)) == null) {
                        // send card request
                        send(i, new RequestCard(deck[i].getArrayNames(j)));

                        // receive file
                        Socket t = fileSocket[i].accept();
                        Utilities.receiveFile(new File(Main.CARDS_DL, 
                                deck[i].getArrayNames(j) + ".jpg"), t);
                        t.close();
                    }
                }
                Debug.p("Missing cards downloaded");

                // start listening to the new client
                serverListeningThreads[i]
                        = new ServerListeningThread(i, ois[i], fileSocket[i]);
                serverListeningThreads[i].start();

                // all clients check all decks
                for (int prev = 0; prev < i; prev++) {
                    ready[prev] = false;
                    // send new deck to already connected clients
                    send(prev, newdeck);
                    // send already connected clients' decks to the new client
                    send(i, new CheckDeck(name[prev], deck[prev]));
                }
            } catch (Exception ex) {
                String ip = socket[i].getLocalAddress() == null?
                    "not received" : "" + socket[i].getLocalAddress();
                String msg = "Error while dealing with player " + i + ": "
                        + "IP = " + ip + ", name = " + name[i]
                        + ", exception = " + ex;
                Debug.p(msg, Debug.W);
                i--;
                try {
                    fileSocket[i].close();
                } catch (Exception ex2) {}
            }
        }
        Debug.p("Game initialisation finished", Debug.I);

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

        //TODO randomize decks and send cards
        // send number of players and their names
    }

    static void send(int player, Action object) {
        if (oos[player] != null) {
            try {
                oos[player].writeObject(object);
                oos[player].flush();
            } catch (IOException ex) {
                Debug.p("Error while sending " + object + " to player "
                        + player + ": " + ex, Debug.E);
            }
        }
    }

    /**
     * Closes all streams and sockets of given player.
     * @param player player
     */
    static void disconnect(int player) {
        try {
            socket[player].close();
        } catch (IOException ex) {}
        try {
            fileSocket[player].close();
        } catch (IOException ex) {}
        fileSocket[player] = null;
        socket[player] = null;
        ois[player] = null;
        oos[player] = null;
    }
}