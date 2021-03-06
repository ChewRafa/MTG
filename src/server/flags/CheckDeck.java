package server.flags;

import mtg.Deck;

/**
 * @author Jaroslaw Pawlak
 *
 * This object may be sent by both client or server - the other side should
 * check if it has all cards in a deck sent
 */
public class CheckDeck extends Action {
    public String owner;
    public Deck deck;

    public CheckDeck(String owner, Deck deck) {
        super(-1);
        this.owner = owner;
        this.deck = deck;
    }

    @Override
    public String toString() {
        return super.toString() + ", owner = " + owner + ")";
    }

}
