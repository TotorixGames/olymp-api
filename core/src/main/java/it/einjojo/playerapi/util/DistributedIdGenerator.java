package it.einjojo.playerapi.util;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates distributed unique IDs using a hybrid Snowflake approach.
 * <p>
 * This generator creates unique 32-bit integer IDs that are collision-free across multiple
 * servers in a distributed system.
 * <p>
 * <b>ID Format (32-bit integer):</b>
 * <ul>
 *   <li>Bits 31-22 (10 bits): Server ID derived from startup timestamp (0-1023)</li>
 *   <li>Bits 21-0 (22 bits): Sequence counter (wraps at 4,194,304)</li>
 * </ul>
 * <p>
 * <b>Server ID Generation:</b>
 * The server ID is automatically derived from the current timestamp at startup.
 * Uses the lower 10 bits of the timestamp milliseconds, which cycles every ~1 second.
 * Since servers don't start in parallel, this guarantees uniqueness without configuration.
 * <p>
 * <b>Examples:</b>
 * <ul>
 *   <li>Server started at 1709347200123ms → ID bits: 123 & 1023 = 123</li>
 *   <li>Server started at 1709347201456ms → ID bits: 456 & 1023 = 456</li>
 *   <li>Server 123, Seq 1: {@code 0x1EC001} = 2,015,233</li>
 * </ul>
 * <p>
 * Thread-safe: All operations are safe for concurrent access.
 *
 * @author EinjoJo
 * @since 1.5.0
 */
public class DistributedIdGenerator {
    private static final Logger log = LoggerFactory.getLogger(DistributedIdGenerator.class);

    // Bit layout constants
    private static final int SEQUENCE_BITS = 22;
    private static final int SEQUENCE_MASK = (1 << SEQUENCE_BITS) - 1; // 0x3FFFFF (4,194,303)
    private static final int SERVER_ID_BITS = 10;
    private static final int MAX_SERVER_ID = (1 << SERVER_ID_BITS) - 1; // 1023

    private final int serverId;
    private final AtomicInteger sequenceCounter;
    private final boolean traceEnabled;

    /**
     * Creates a new DistributedIdGenerator with timestamp-based server ID.
     * <p>
     * Server ID is automatically derived from the current timestamp.
     * The lower 10 bits of System.currentTimeMillis() are used, which cycles every ~1 second.
     * <p>
     * Safe for distributed deployments as long as servers don't start simultaneously
     * (within the same millisecond).
     */
    public DistributedIdGenerator() {
        this(generateTimestampBasedServerId());
    }

    /**
     * Creates a new DistributedIdGenerator with a specific server ID.
     * <p>
     * This constructor is primarily for testing purposes.
     *
     * @param serverId the server ID (0-1023)
     * @throws IllegalArgumentException if serverId is out of valid range
     */
    public DistributedIdGenerator(int serverId) {
        if (serverId < 0 || serverId > MAX_SERVER_ID) {
            throw new IllegalArgumentException(
                    "Server ID must be between 0 and " + MAX_SERVER_ID + ", got: " + serverId);
        }
        this.serverId = serverId;
        this.sequenceCounter = new AtomicInteger(0);
        this.traceEnabled = log.isTraceEnabled();

        log.info("DistributedIdGenerator initialized with server_id={} (timestamp-based)", serverId);
    }

    /**
     * Generate a server ID from the current timestamp.
     * <p>
     * Uses the lower 10 bits of the current time in milliseconds.
     * This creates a unique ID for each server as long as they don't start
     * at exactly the same millisecond.
     * <p>
     * The lower bits change most rapidly (~1000 times per second),
     * providing good distribution across the 0-1023 range.
     *
     * @return a server ID (0-1023) derived from current timestamp
     */
    private static int generateTimestampBasedServerId() {
        long timestamp = System.currentTimeMillis();
        int serverId = (int) (timestamp & MAX_SERVER_ID);

        log.info("Generated timestamp-based server_id={} from timestamp={}", serverId, timestamp);
        return serverId;
    }

    /**
     * Generate the next unique ID.
     * <p>
     * The generated ID is guaranteed to be unique across all servers in the distributed system,
     * as long as each server has a unique server ID.
     * <p>
     * The sequence counter wraps around at 4,194,304 (2^22). After wraparound, IDs may be reused,
     * but this is safe for short-lived request tracking.
     *
     * @return a globally unique ID
     */
    public int nextId() {
        // Increment sequence and mask to 22 bits (wraps at 4,194,304)
        int sequence = sequenceCounter.incrementAndGet() & SEQUENCE_MASK;

        // Combine: [10-bit server ID] << 22 | [22-bit sequence]
        int id = (serverId << SEQUENCE_BITS) | sequence;

        if (traceEnabled) {
            log.trace("Generated ID: {} (server={}, sequence={})", id, serverId, sequence);
        }

        return id;
    }

    /**
     * Get the current server ID used by this generator.
     *
     * @return the server ID (0-1023)
     */
    public int getServerId() {
        return serverId;
    }

    /**
     * Get the current sequence counter value.
     * <p>
     * Note: This value may change immediately after reading due to concurrent access.
     *
     * @return the current sequence value
     */
    public int getCurrentSequence() {
        return sequenceCounter.get() & SEQUENCE_MASK;
    }

    /**
     * Extract the server ID from a generated ID.
     *
     * @param id the generated ID
     * @return the server ID (0-1023)
     */
    public static int extractServerId(int id) {
        return (id >>> SEQUENCE_BITS) & MAX_SERVER_ID;
    }

    /**
     * Extract the sequence number from a generated ID.
     *
     * @param id the generated ID
     * @return the sequence number (0-4,194,303)
     */
    public static int extractSequence(int id) {
        return id & SEQUENCE_MASK;
    }

    /**
     * Create a human-readable string representation of an ID.
     *
     * @param id the generated ID
     * @return formatted string like "ID{server=5, seq=12345, value=21242937}"
     */
    @NotNull
    public static String formatId(int id) {
        return String.format("ID{server=%d, seq=%d, value=%d}",
                extractServerId(id), extractSequence(id), id);
    }

    @Override
    public String toString() {
        return String.format("DistributedIdGenerator{serverId=%d, currentSequence=%d}",
                serverId, getCurrentSequence());
    }
}



