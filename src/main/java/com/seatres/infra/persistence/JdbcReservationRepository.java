package com.seatres.infra.persistence;

import com.seatres.application.port.ReservationRepository;
import com.seatres.domain.Booking;
import com.seatres.domain.EventInfo;
import com.seatres.domain.HoldStatus;
import com.seatres.domain.HoldView;
import com.seatres.domain.SeatChange;
import com.seatres.domain.SeatStatus;
import com.seatres.domain.SeatView;
import com.seatres.domain.WaitlistStatus;
import com.seatres.domain.WaitlistView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The entire correctness story is in these statements. Three rules, enforced everywhere:
 *
 * <ol>
 *   <li>state changes are conditional UPDATEs — the WHERE clause decides the race, atomically;
 *   <li>the database's {@code now()} is the only clock — it is transaction-stable, so a claim's
 *       hold row and seat rows always get the identical expiry timestamp;
 *   <li>expired holds are claimable by anyone — unless someone is waiting: the waitlist has first
 *       claim, and the seat row itself serializes freeing, joining, and claiming.
 * </ol>
 *
 * Runs at READ COMMITTED (deliberate): after a row-lock wait, Postgres re-evaluates the WHERE
 * against the newly committed version, so the loser of a race simply matches zero rows.
 */
@Repository
public class JdbcReservationRepository implements ReservationRepository {

  private static final String SEAT_RETURNING =
      " RETURNING event_id, id, row_label, seat_number, status, hold_expires_at, version";

  /** No live waiters: the waitlist outranks any direct claim, including lazy reclaims. */
  private static final String NO_LIVE_WAITERS_FOR = // parameter: seat id column reference
      "NOT EXISTS (SELECT 1 FROM waitlist w WHERE w.seat_id = %s AND w.status = 'WAITING')";

  /** Inner SELECT locks in sorted id order so concurrent overlapping claims cannot deadlock. */
  private static final String CLAIM_SEATS =
      """
      UPDATE seat
         SET status = 'HELD', hold_id = :holdId,
             hold_expires_at = now() + make_interval(secs => :ttl),
             version = version + 1
       WHERE id IN (SELECT s.id
                      FROM seat s
                     WHERE s.id IN (:seatIds) AND s.event_id = :eventId
                       AND (s.status = 'AVAILABLE'
                            OR (s.status = 'HELD' AND s.hold_expires_at <= now()))
                       AND
      """
          + NO_LIVE_WAITERS_FOR.formatted("s.id")
          + """
                     ORDER BY s.id
                       FOR UPDATE)
      """
          + SEAT_RETURNING;

  private static final String INSERT_HOLD =
      """
      INSERT INTO hold (id, event_id, user_ref, status, seat_count, expires_at)
      VALUES (:id, :eventId, :userRef, 'ACTIVE', :seatCount, now() + make_interval(secs => :ttl))
      """;

  /** The serialization point for confirm: decided on one row, in one statement. */
  private static final String CAS_CONFIRM_HOLD =
      """
      UPDATE hold SET status = 'CONFIRMED'
       WHERE id = :holdId AND status = 'ACTIVE' AND expires_at > now()
      RETURNING seat_count, event_id, user_ref
      """;

  private static final String CAS_RELEASE_HOLD =
      "UPDATE hold SET status = 'RELEASED' WHERE id = :holdId AND status = 'ACTIVE'";

  private static final String MARK_SEATS_SOLD =
      """
      UPDATE seat SET status = 'SOLD', hold_expires_at = NULL, version = version + 1
       WHERE hold_id = :holdId AND status = 'HELD'
      """
          + SEAT_RETURNING;

  private static final String INSERT_BOOKING =
      """
      INSERT INTO booking (id, hold_id, event_id, user_ref)
      VALUES (:id, :holdId, :eventId, :userRef)
      """;

  /** booking_seat.seat_id is the PRIMARY KEY: double-sell is structurally impossible here. */
  private static final String INSERT_BOOKING_SEATS =
      """
      INSERT INTO booking_seat (seat_id, booking_id)
      SELECT id, :bookingId FROM seat WHERE hold_id = :holdId AND status = 'SOLD'
      """;

  private static final String EXPIRE_DUE_HOLDS =
      """
      WITH due AS (SELECT id FROM hold
                    WHERE status = 'ACTIVE' AND expires_at <= now()
                    ORDER BY expires_at
                    LIMIT :limit
                      FOR UPDATE SKIP LOCKED)
      UPDATE hold h SET status = 'EXPIRED' FROM due WHERE h.id = due.id
      RETURNING h.id
      """;

  // --- Seat routing: the seat row is the serialization point ---

  private static final String SEAT_IDS_OF_HOLD =
      "SELECT id FROM seat WHERE hold_id = :holdId AND status = 'HELD' ORDER BY id";

  private static final String LOCK_FREED_SEAT =
      """
      SELECT 1 FROM seat
       WHERE id = :seatId AND hold_id = :oldHoldId AND status = 'HELD'
         FOR UPDATE
      """;

  private static final String POOL_SEAT =
      """
      UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL,
                      version = version + 1
       WHERE id = :seatId
      """
          + SEAT_RETURNING;

  private static final String ASSIGN_SEAT_TO_HOLD =
      """
      UPDATE seat SET hold_id = :holdId,
             hold_expires_at = now() + make_interval(secs => :ttl),
             version = version + 1
       WHERE id = :seatId
      """
          + SEAT_RETURNING;

  // --- Waitlist ---

  private static final String LOCK_SEAT_FOR_JOIN =
      "SELECT status, event_id FROM seat WHERE id = :seatId FOR SHARE";

  private static final String INSERT_WAITLIST =
      """
      INSERT INTO waitlist (id, seat_id, event_id, user_ref, status)
      VALUES (:id, :seatId, :eventId, :userRef, 'WAITING')
      """;

  private static final String FIND_WAITLIST_ENTRY =
      """
      SELECT w.id, w.seat_id, w.event_id, w.user_ref, w.status, w.granted_hold_id,
             s.row_label, s.seat_number,
             CASE WHEN w.status = 'WAITING'
                  THEN (SELECT count(*) + 1 FROM waitlist w2
                         WHERE w2.seat_id = w.seat_id AND w2.status = 'WAITING'
                           AND w2.position < w.position)
                  ELSE 0 END AS live_position
        FROM waitlist w
        JOIN seat s ON s.id = w.seat_id
       WHERE w.id = :entryId
      """;

  /** Plain read on purpose: no lock (cancel CAS absorbs races), no SKIP LOCKED (fairness). */
  private static final String HEAD_WAITER =
      """
      SELECT id, event_id, user_ref FROM waitlist
       WHERE seat_id = :seatId AND status = 'WAITING'
       ORDER BY position
       LIMIT 1
      """;

  private static final String CAS_GRANT_WAITLIST =
      """
      UPDATE waitlist SET status = 'GRANTED', granted_hold_id = :holdId
       WHERE id = :entryId AND status = 'WAITING'
      """;

  private static final String CAS_CANCEL_WAITLIST =
      "UPDATE waitlist SET status = 'CANCELLED' WHERE id = :entryId AND status = 'WAITING'";

  private static final String CANCEL_WAITING_ON_SEATS =
      "UPDATE waitlist SET status = 'CANCELLED' WHERE seat_id IN (:seatIds) AND status = 'WAITING'";

  private static final String SEAT_MAP =
      """
      SELECT id, row_label, seat_number,
             CASE WHEN status = 'HELD' AND hold_expires_at <= now() THEN 'AVAILABLE'
                  ELSE status END AS status,
             CASE WHEN status = 'HELD' AND hold_expires_at > now() THEN hold_expires_at
                  END AS hold_expires_at,
             version
        FROM seat
       WHERE event_id = :eventId
       ORDER BY row_label, seat_number
      """;

  private static final String CLAIMABLE_NOW =
      """
      SELECT id FROM seat
       WHERE event_id = :eventId AND id IN (:seatIds)
         AND (status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= now()))
         AND
      """
          + NO_LIVE_WAITERS_FOR.formatted("seat.id");

  private static final String FIND_HOLD =
      """
      SELECT h.id, h.event_id, h.user_ref, h.expires_at,
             CASE WHEN h.status = 'ACTIVE' AND h.expires_at <= now() THEN 'EXPIRED'
                  ELSE h.status END AS status,
             CASE WHEN h.status = 'ACTIVE'
                  THEN GREATEST(0, CEIL(EXTRACT(EPOCH FROM (h.expires_at - now()))))::bigint
                  ELSE 0 END AS remaining_seconds,
             (SELECT COALESCE(array_agg(s.id ORDER BY s.row_label, s.seat_number), '{}')
                FROM seat s WHERE s.hold_id = h.id) AS seat_ids
        FROM hold h
       WHERE h.id = :holdId
      """;

  private static final String BOOKING_SELECT =
      """
      SELECT b.id, b.hold_id, b.event_id, b.user_ref, b.confirmed_at,
             (SELECT COALESCE(array_agg(bs.seat_id), '{}')
                FROM booking_seat bs WHERE bs.booking_id = b.id) AS seat_ids
        FROM booking b
      """;

  private static final RowMapper<SeatChange> SEAT_CHANGE =
      (rs, i) ->
          new SeatChange(
              rs.getObject("event_id", UUID.class),
              rs.getObject("id", UUID.class),
              rs.getString("row_label"),
              rs.getInt("seat_number"),
              SeatStatus.valueOf(rs.getString("status")),
              instant(rs, "hold_expires_at"),
              rs.getLong("version"));

  private static final RowMapper<SeatView> SEAT_VIEW =
      (rs, i) ->
          new SeatView(
              rs.getObject("id", UUID.class),
              rs.getString("row_label"),
              rs.getInt("seat_number"),
              SeatStatus.valueOf(rs.getString("status")),
              instant(rs, "hold_expires_at"),
              rs.getLong("version"));

  private static final RowMapper<HoldView> HOLD_VIEW =
      (rs, i) ->
          new HoldView(
              rs.getObject("id", UUID.class),
              rs.getObject("event_id", UUID.class),
              rs.getString("user_ref"),
              HoldStatus.valueOf(rs.getString("status")),
              uuids(rs, "seat_ids"),
              instant(rs, "expires_at"),
              rs.getLong("remaining_seconds"));

  private static final RowMapper<Booking> BOOKING =
      (rs, i) ->
          new Booking(
              rs.getObject("id", UUID.class),
              rs.getObject("hold_id", UUID.class),
              rs.getObject("event_id", UUID.class),
              rs.getString("user_ref"),
              instant(rs, "confirmed_at"),
              uuids(rs, "seat_ids"));

  private static final RowMapper<EventInfo> EVENT_INFO =
      (rs, i) ->
          new EventInfo(
              rs.getObject("id", UUID.class), rs.getString("name"), instant(rs, "starts_at"));

  private static final RowMapper<WaitlistView> WAITLIST_VIEW =
      (rs, i) ->
          new WaitlistView(
              rs.getObject("id", UUID.class),
              rs.getObject("seat_id", UUID.class),
              rs.getString("row_label") + rs.getInt("seat_number"),
              rs.getObject("event_id", UUID.class),
              rs.getString("user_ref"),
              WaitlistStatus.valueOf(rs.getString("status")),
              rs.getLong("live_position"),
              rs.getObject("granted_hold_id", UUID.class));

  private final JdbcClient jdbc;

  public JdbcReservationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<EventInfo> listEvents() {
    return jdbc.sql("SELECT id, name, starts_at FROM event ORDER BY starts_at")
        .query(EVENT_INFO)
        .list();
  }

  @Override
  public Optional<EventInfo> findEvent(UUID eventId) {
    return jdbc.sql("SELECT id, name, starts_at FROM event WHERE id = :id")
        .param("id", eventId)
        .query(EVENT_INFO)
        .optional();
  }

  @Override
  public List<SeatView> seatMap(UUID eventId) {
    return jdbc.sql(SEAT_MAP).param("eventId", eventId).query(SEAT_VIEW).list();
  }

  @Override
  public List<UUID> claimableNow(UUID eventId, List<UUID> seatIds) {
    return jdbc.sql(CLAIMABLE_NOW)
        .param("eventId", eventId)
        .param("seatIds", seatIds)
        .query(UUID.class)
        .list();
  }

  @Override
  public Optional<HoldView> findHold(UUID holdId) {
    return jdbc.sql(FIND_HOLD).param("holdId", holdId).query(HOLD_VIEW).optional();
  }

  @Override
  public Optional<Booking> findBooking(UUID bookingId) {
    return jdbc.sql(BOOKING_SELECT + " WHERE b.id = :id")
        .param("id", bookingId)
        .query(BOOKING)
        .optional();
  }

  @Override
  public Optional<Booking> findBookingByHold(UUID holdId) {
    return jdbc.sql(BOOKING_SELECT + " WHERE b.hold_id = :holdId")
        .param("holdId", holdId)
        .query(BOOKING)
        .optional();
  }

  @Override
  public void insertHold(
      UUID holdId, UUID eventId, String userRef, int seatCount, double ttlSeconds) {
    jdbc.sql(INSERT_HOLD)
        .param("id", holdId)
        .param("eventId", eventId)
        .param("userRef", userRef)
        .param("seatCount", seatCount)
        .param("ttl", ttlSeconds)
        .update();
  }

  @Override
  public List<SeatChange> claimSeats(
      UUID holdId, UUID eventId, List<UUID> seatIds, double ttlSeconds) {
    return jdbc.sql(CLAIM_SEATS)
        .param("holdId", holdId)
        .param("eventId", eventId)
        .param("seatIds", seatIds)
        .param("ttl", ttlSeconds)
        .query(SEAT_CHANGE)
        .list();
  }

  @Override
  public Optional<HoldCas> casConfirmHold(UUID holdId) {
    return jdbc.sql(CAS_CONFIRM_HOLD)
        .param("holdId", holdId)
        .query(
            (rs, i) ->
                new HoldCas(
                    rs.getInt("seat_count"),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("user_ref")))
        .optional();
  }

  @Override
  public int casReleaseHold(UUID holdId) {
    return jdbc.sql(CAS_RELEASE_HOLD).param("holdId", holdId).update();
  }

  @Override
  public List<SeatChange> markSeatsSold(UUID holdId) {
    return jdbc.sql(MARK_SEATS_SOLD).param("holdId", holdId).query(SEAT_CHANGE).list();
  }

  @Override
  public void insertBooking(UUID bookingId, UUID holdId, UUID eventId, String userRef) {
    jdbc.sql(INSERT_BOOKING)
        .param("id", bookingId)
        .param("holdId", holdId)
        .param("eventId", eventId)
        .param("userRef", userRef)
        .update();
  }

  @Override
  public int insertBookingSeats(UUID bookingId, UUID holdId) {
    return jdbc.sql(INSERT_BOOKING_SEATS)
        .param("bookingId", bookingId)
        .param("holdId", holdId)
        .update();
  }

  @Override
  public List<UUID> expireDueHolds(int limit) {
    return jdbc.sql(EXPIRE_DUE_HOLDS).param("limit", limit).query(UUID.class).list();
  }

  // --- Seat routing ---

  @Override
  public List<UUID> seatIdsOfHold(UUID holdId) {
    return jdbc.sql(SEAT_IDS_OF_HOLD).param("holdId", holdId).query(UUID.class).list();
  }

  @Override
  public int lockFreedSeat(UUID seatId, UUID oldHoldId) {
    return jdbc.sql(LOCK_FREED_SEAT)
        .param("seatId", seatId)
        .param("oldHoldId", oldHoldId)
        .query(Integer.class)
        .list()
        .size();
  }

  @Override
  public SeatChange poolSeat(UUID seatId) {
    return jdbc.sql(POOL_SEAT).param("seatId", seatId).query(SEAT_CHANGE).single();
  }

  @Override
  public SeatChange assignSeatToHold(UUID seatId, UUID holdId, double ttlSeconds) {
    return jdbc.sql(ASSIGN_SEAT_TO_HOLD)
        .param("seatId", seatId)
        .param("holdId", holdId)
        .param("ttl", ttlSeconds)
        .query(SEAT_CHANGE)
        .single();
  }

  @Override
  public void deleteHold(UUID holdId) {
    jdbc.sql("DELETE FROM hold WHERE id = :holdId").param("holdId", holdId).update();
  }

  // --- Waitlist ---

  @Override
  public Optional<LockedSeat> lockSeatForJoin(UUID seatId) {
    return jdbc.sql(LOCK_SEAT_FOR_JOIN)
        .param("seatId", seatId)
        .query(
            (rs, i) -> new LockedSeat(rs.getString("status"), rs.getObject("event_id", UUID.class)))
        .optional();
  }

  @Override
  public void insertWaitlistEntry(UUID entryId, UUID seatId, UUID eventId, String userRef) {
    jdbc.sql(INSERT_WAITLIST)
        .param("id", entryId)
        .param("seatId", seatId)
        .param("eventId", eventId)
        .param("userRef", userRef)
        .update();
  }

  @Override
  public Optional<WaitlistView> findWaitlistEntry(UUID entryId) {
    return jdbc.sql(FIND_WAITLIST_ENTRY).param("entryId", entryId).query(WAITLIST_VIEW).optional();
  }

  @Override
  public Optional<HeadWaiter> headWaiter(UUID seatId) {
    return jdbc.sql(HEAD_WAITER)
        .param("seatId", seatId)
        .query(
            (rs, i) ->
                new HeadWaiter(
                    rs.getObject("id", UUID.class),
                    rs.getObject("event_id", UUID.class),
                    rs.getString("user_ref")))
        .optional();
  }

  @Override
  public int casGrantWaitlist(UUID entryId, UUID holdId) {
    return jdbc.sql(CAS_GRANT_WAITLIST).param("entryId", entryId).param("holdId", holdId).update();
  }

  @Override
  public int casCancelWaitlist(UUID entryId) {
    return jdbc.sql(CAS_CANCEL_WAITLIST).param("entryId", entryId).update();
  }

  @Override
  public int cancelWaitingOnSeats(List<UUID> seatIds) {
    return jdbc.sql(CANCEL_WAITING_ON_SEATS).param("seatIds", seatIds).update();
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
    return t == null ? null : t.toInstant();
  }

  private static List<UUID> uuids(ResultSet rs, String column) throws SQLException {
    java.sql.Array array = rs.getArray(column);
    if (array == null) {
      return List.of();
    }
    return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
  }
}
