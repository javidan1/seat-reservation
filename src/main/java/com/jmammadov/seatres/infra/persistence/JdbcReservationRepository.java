package com.jmammadov.seatres.infra.persistence;

import com.jmammadov.seatres.application.port.ReservationRepository;
import com.jmammadov.seatres.domain.Booking;
import com.jmammadov.seatres.domain.EventInfo;
import com.jmammadov.seatres.domain.HoldStatus;
import com.jmammadov.seatres.domain.HoldView;
import com.jmammadov.seatres.domain.SeatChange;
import com.jmammadov.seatres.domain.SeatStatus;
import com.jmammadov.seatres.domain.SeatView;
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
 *   <li>expired holds are claimable by anyone: the sweeper is a janitor, not a dependency.
 * </ol>
 *
 * Runs at READ COMMITTED (deliberate): after a row-lock wait, Postgres re-evaluates the WHERE
 * against the newly committed version, so the loser of a race simply matches zero rows.
 */
@Repository
public class JdbcReservationRepository implements ReservationRepository {

  private static final String SEAT_RETURNING =
      " RETURNING event_id, id, row_label, seat_number, status, hold_expires_at, version";

  /** Effective availability: a lapsed hold's seat is claimable without waiting for the sweeper. */
  private static final String CLAIMABLE =
      "(status = 'AVAILABLE' OR (status = 'HELD' AND hold_expires_at <= now()))";

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

  private static final String RELEASE_SEATS =
      """
      UPDATE seat SET status = 'AVAILABLE', hold_id = NULL, hold_expires_at = NULL,
                      version = version + 1
       WHERE hold_id IN (:holdIds) AND status = 'HELD'
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
      "SELECT id FROM seat WHERE event_id = :eventId AND id IN (:seatIds) AND " + CLAIMABLE;

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
  public List<SeatChange> releaseSeats(UUID holdId) {
    return releaseSeatsOfHolds(List.of(holdId));
  }

  @Override
  public List<SeatChange> releaseSeatsOfHolds(List<UUID> holdIds) {
    return jdbc.sql(RELEASE_SEATS).param("holdIds", holdIds).query(SEAT_CHANGE).list();
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
