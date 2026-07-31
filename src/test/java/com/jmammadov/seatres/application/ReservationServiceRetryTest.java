package com.jmammadov.seatres.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jmammadov.seatres.application.port.ReservationRepository;
import com.jmammadov.seatres.domain.Booking;
import com.jmammadov.seatres.domain.EventInfo;
import com.jmammadov.seatres.domain.HoldStatus;
import com.jmammadov.seatres.domain.HoldView;
import com.jmammadov.seatres.domain.SeatChange;
import com.jmammadov.seatres.domain.SeatStatus;
import com.jmammadov.seatres.domain.SeatView;
import com.jmammadov.seatres.domain.WaitlistView;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one unit test in this codebase, and deliberately so. Everywhere else, the defects that matter
 * (races, predicate semantics, rowcounts, lock behavior) live below the mock line — a mocked
 * repository can only verify its own stubs, so those paths are proven against real Postgres in the
 * integration suites. This branch is the exception: the deadlock retry in {@code hold()} cannot be
 * triggered deterministically through the API (sorted locking makes real deadlocks near-impossible
 * — that's the point), which is exactly the seam where a hand-rolled stub outperforms the real
 * database.
 */
class ReservationServiceRetryTest {

  private static final UUID EVENT = UUID.randomUUID();
  private static final UUID SEAT = UUID.randomUUID();

  /** Throws deadlock from claimSeats the first {@code deadlocks} times, then succeeds. */
  private static final class DeadlockingRepositoryStub implements ReservationRepository {
    int remainingDeadlocks;
    int claimCalls;
    int holdInserts;

    DeadlockingRepositoryStub(int deadlocks) {
      this.remainingDeadlocks = deadlocks;
    }

    @Override
    public Optional<EventInfo> findEvent(UUID eventId) {
      return Optional.of(new EventInfo(eventId, "test", Instant.now()));
    }

    @Override
    public void insertHold(UUID holdId, UUID e, String u, int seatCount, double ttl) {
      holdInserts++;
    }

    @Override
    public List<SeatChange> claimSeats(UUID holdId, UUID e, List<UUID> seatIds, double ttl) {
      claimCalls++;
      if (remainingDeadlocks-- > 0) {
        throw new PessimisticLockingFailureException("deadlock detected (simulated)");
      }
      return List.of(new SeatChange(e, SEAT, "A", 1, SeatStatus.HELD, Instant.now(), 1));
    }

    @Override
    public Optional<HoldView> findHold(UUID holdId) {
      return Optional.of(
          new HoldView(holdId, EVENT, "u", HoldStatus.ACTIVE, List.of(SEAT), Instant.now(), 60));
    }

    // Nothing below is reachable from the retry path under test.
    @Override
    public List<EventInfo> listEvents() {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SeatView> seatMap(UUID eventId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> claimableNow(UUID eventId, List<UUID> seatIds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Booking> findBooking(UUID bookingId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Booking> findBookingByHold(UUID holdId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<HoldCas> casConfirmHold(UUID holdId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int casReleaseHold(UUID holdId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<SeatChange> markSeatsSold(UUID holdId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertBooking(UUID b, UUID h, UUID e, String u) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int insertBookingSeats(UUID bookingId, UUID holdId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> expireDueHolds(int limit) {
      throw new UnsupportedOperationException();
    }

    @Override
    public List<UUID> seatIdsOfHold(UUID holdId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int lockFreedSeat(UUID seatId, UUID oldHoldId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SeatChange poolSeat(UUID seatId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SeatChange assignSeatToHold(UUID seatId, UUID holdId, double ttlSeconds) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void deleteHold(UUID holdId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<LockedSeat> lockSeatForJoin(UUID seatId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void insertWaitlistEntry(UUID entryId, UUID seatId, UUID eventId, String userRef) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<WaitlistView> findWaitlistEntry(UUID entryId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<HeadWaiter> headWaiter(UUID seatId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int casGrantWaitlist(UUID entryId, UUID holdId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int casCancelWaitlist(UUID entryId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int cancelWaitingOnSeats(List<UUID> seatIds) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {}

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }

  private static ReservationService service(DeadlockingRepositoryStub repo) {
    var properties =
        new SeatresProperties(
            Duration.ofSeconds(60), new SeatresProperties.Sweeper(false, Duration.ofSeconds(1)));
    return new ReservationService(
        repo,
        new TransactionTemplate(new NoOpTransactionManager()),
        event -> {},
        properties,
        new SeatReleaseService(repo, properties));
  }

  @Test
  void holdRetriesExactlyOnceAfterDeadlockAndSucceeds() {
    var repo = new DeadlockingRepositoryStub(1);

    HoldView hold = service(repo).hold(EVENT, "alice", List.of(SEAT));

    assertThat(hold.seatIds()).containsExactly(SEAT);
    assertThat(repo.claimCalls).isEqualTo(2);
    assertThat(repo.holdInserts)
        .as("each attempt is a fresh transaction with a fresh hold")
        .isEqualTo(2);
  }

  @Test
  void holdGivesUpAfterTheSecondDeadlock() {
    var repo = new DeadlockingRepositoryStub(2);

    assertThatThrownBy(() -> service(repo).hold(EVENT, "alice", List.of(SEAT)))
        .isInstanceOf(PessimisticLockingFailureException.class);
    assertThat(repo.claimCalls).isEqualTo(2); // one retry, not an unbounded loop
  }
}
