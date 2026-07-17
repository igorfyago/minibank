package dev.minibank.ledger;

import java.sql.Connection;
import java.sql.SQLException;

/** "Give me a connection to YOUR database." Once the bank has more than one
 *  database (stage 5: shards), every component that talks to "the" database
 *  must be told which one — this is that address, as a function. */
@FunctionalInterface
public interface ConnectionSource {
    Connection open() throws SQLException;
}
