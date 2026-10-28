package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleRepository.*;
import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;

import com.newsswing.core.news.ingestion.NewsIngestionProperties;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
final class JdbcInferenceJobLifecycleService implements InferenceJobLifecycleService {
  private final InferenceJobLifecycleRepository repository;
  private final InferenceJobLifecycleProperties properties;
  private final int capacity;
  private final TransactionTemplate transactions;
  private final TransactionTemplate discoveryTransactions;

  JdbcInferenceJobLifecycleService(
      InferenceJobLifecycleRepository repository,
      InferenceJobLifecycleProperties properties,
      NewsIngestionProperties ingestion,
      PlatformTransactionManager manager) {
    this.repository = repository;
    this.properties = properties;
    this.capacity = ingestion.pendingCapacity();
    this.transactions = new TransactionTemplate(manager);
    this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.discoveryTransactions = new TransactionTemplate(manager);
    this.discoveryTransactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.discoveryTransactions.setReadOnly(true);
  }

  @Override
  public ClaimOutcome claimNext() {
    rejectAmbientTransaction();
    recoverBatch();
    var candidates = discover(() -> repository.discoverPending(properties.recoveryBatchSize()));
    for (var candidate : candidates) {
      ClaimOutcome outcome = write(() -> claimCandidate(candidate));
      if (outcome instanceof Claimed) {
        // TransactionTemplate returns only after a successful commit.
        return outcome;
      }
    }
    return new NoWork();
  }

  private ClaimOutcome claimCandidate(Candidate candidate) {
    var locked = lockCandidate(candidate, true);
    if (locked == null) {
      return new NoWork();
    }
    var job = locked.job();
    UUID token = UUID.randomUUID();
    UUID inferenceId = UUID.randomUUID();
    Instant now = repository.now();
    if (job.status() != JobStatus.PENDING
        || !job.revisionId().equals(locked.item().latestEventId())
        || !job.deadlineAt().isAfter(now)
        || job.assignmentCount() >= job.maxAssignments()) {
      return new NoWork();
    }
    Instant expiry = min(now.plus(properties.leaseDuration()), job.deadlineAt());
    repository.claim(job, token, inferenceId, now, expiry);
    return new Claimed(
        new Assignment(
            new AssignmentRef(job.jobId(), job.assignmentCount() + 1, token),
            job.eventPayload(),
            inferenceId,
            job.expectedBackend(),
            "1.0.0",
            expiry));
  }

  @Override
  public RenewOutcome renew(AssignmentRef assignment) {
    rejectAmbientTransaction();
    if (!valid(assignment)) {
      return new Rejected(RejectionReason.INVALID_COMMAND);
    }
    return write(
        () -> {
          var locked = lockOwner(assignment);
          Instant now = locked == null ? null : repository.now();
          var rejected = rejection(assignment, locked, now);
          if (rejected != null) {
            return rejected;
          }
          var job = locked.job();
          Instant expiry =
              min(
                  job.deadlineAt(),
                  max(job.leaseExpiresAt(), now.plus(properties.leaseDuration())));
          repository.renew(job, expiry);
          return new Renewed(expiry);
        });
  }

  @Override
  public FailOutcome fail(AssignmentRef assignment, FailureKind kind) {
    rejectAmbientTransaction();
    if (!valid(assignment) || kind == null) {
      return new Rejected(RejectionReason.INVALID_COMMAND);
    }
    return write(
        () -> {
          var locked = lockOwner(assignment);
          Instant now = locked == null ? null : repository.now();
          var rejected = rejection(assignment, locked, now);
          if (rejected != null) {
            return rejected;
          }
          var job = locked.job();
          FailureCode code =
              kind == FailureKind.RECOVERABLE
                  ? FailureCode.WORKER_RECOVERABLE
                  : FailureCode.WORKER_PERMANENT;
          if (kind == FailureKind.RECOVERABLE && job.assignmentCount() < job.maxAssignments()) {
            repository.retryWaiting(job, code, now);
            return new RetryWaiting();
          }
          repository.fail(job, code, now);
          return new Failed(code);
        });
  }

  @Override
  public RecoverySummary recoverBatch() {
    rejectAmbientTransaction();
    var progress = new Progress();
    UUID activeJob = null;
    try {
      var cleanup =
          discover(
              () -> repository.discoverCleanup(repository.now(), properties.recoveryBatchSize()));
      for (var candidate : cleanup) {
        activeJob = candidate.jobId();
        var outcome = write(() -> recoverCandidate(candidate));
        progress.add(outcome); // Count only after commit.
      }
      activeJob = null;
      int remaining = properties.recoveryBatchSize() - progress.processed;
      if (remaining > 0) {
        var retries = discover(() -> repository.discoverRetries(remaining));
        for (var candidate : retries) {
          activeJob = candidate.jobId();
          var outcome = write(() -> recoverCandidate(candidate));
          progress.add(outcome);
        }
      }
      return progress.summary();
    } catch (RuntimeException failure) {
      throw new RecoveryInfrastructureException(progress.summary(), activeJob, failure);
    }
  }

  private RecoveryOutcome recoverCandidate(Candidate candidate) {
    var locked = lockCandidate(candidate, true);
    if (locked == null || locked.job().status().isTerminal()) {
      return RecoveryOutcome.SKIPPED;
    }
    var job = locked.job();
    // Count under the mutex before the final decision sample, so no blocking preparation
    // can make a previously sampled instant stale.
    long pending = job.status() == JobStatus.RETRY_WAIT ? repository.countPending() : 0;
    Instant now = repository.now();
    if (!job.revisionId().equals(locked.item().latestEventId())) {
      repository.supersede(job, locked.item().latestEventId(), now);
      return RecoveryOutcome.SUPERSEDED;
    }
    if (!job.deadlineAt().isAfter(now)) {
      repository.expire(job, now);
      return RecoveryOutcome.EXPIRED;
    }
    if (job.status() == JobStatus.RUNNING && !job.leaseExpiresAt().isAfter(now)) {
      if (job.assignmentCount() < job.maxAssignments()) {
        repository.retryWaiting(job, FailureCode.LEASE_EXPIRED, now);
        return RecoveryOutcome.RETRY_WAITING;
      }
      repository.fail(job, FailureCode.LEASE_EXPIRED, now);
      return RecoveryOutcome.FAILED;
    }
    if (job.status() == JobStatus.RETRY_WAIT) {
      if (pending + 1 > capacity) {
        return RecoveryOutcome.CAPACITY_DEFERRED;
      }
      repository.requeue(job, now);
      return RecoveryOutcome.REQUEUED;
    }
    return RecoveryOutcome.SKIPPED;
  }

  private Locked lockOwner(AssignmentRef assignment) {
    var candidate = repository.findCandidate(assignment.jobId());
    return candidate == null ? null : lockCandidate(candidate, false);
  }

  private Locked lockCandidate(Candidate candidate, boolean skipLocked) {
    var item = repository.lockItem(candidate.itemId(), skipLocked);
    if (item == null) {
      return null;
    }
    repository.lockAdmission();
    var job = repository.lockJob(candidate.jobId());
    return job == null ? null : new Locked(item, job);
  }

  private static Rejected rejection(AssignmentRef ref, Locked locked, Instant now) {
    if (locked == null) {
      return new Rejected(RejectionReason.NOT_FOUND);
    }
    var job = locked.job();
    if (job.status() != JobStatus.RUNNING) {
      return new Rejected(RejectionReason.NOT_RUNNING);
    }
    if (job.assignmentCount() != ref.assignmentNumber()
        || !Objects.equals(job.token(), ref.ownershipToken())) {
      return new Rejected(RejectionReason.OWNER_MISMATCH);
    }
    if (!job.revisionId().equals(locked.item().latestEventId())) {
      return new Rejected(RejectionReason.REVISION_OBSOLETE);
    }
    if (!job.deadlineAt().isAfter(now)) {
      return new Rejected(RejectionReason.NEWS_EXPIRED);
    }
    if (!job.leaseExpiresAt().isAfter(now)) {
      return new Rejected(RejectionReason.LEASE_EXPIRED);
    }
    return null;
  }

  private static boolean valid(AssignmentRef ref) {
    return ref != null
        && ref.jobId() != null
        && ref.ownershipToken() != null
        && ref.assignmentNumber() > 0;
  }

  private static void rejectAmbientTransaction() {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalTransactionStateException("Lifecycle entry points own their transactions");
    }
  }

  private <T> T write(Supplier<T> action) {
    return Objects.requireNonNull(
        transactions.execute(
            ignored -> {
              repository.configureTransaction();
              return action.get();
            }));
  }

  private <T> T discover(Supplier<T> action) {
    return Objects.requireNonNull(
        discoveryTransactions.execute(
            ignored -> {
              repository.configureTransaction();
              return action.get();
            }));
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private record Locked(ItemRow item, JobRow job) {}

  private enum RecoveryOutcome {
    SKIPPED,
    SUPERSEDED,
    EXPIRED,
    RETRY_WAITING,
    FAILED,
    REQUEUED,
    CAPACITY_DEFERRED
  }

  private static final class Progress {
    int processed;
    final int[] outcomes = new int[RecoveryOutcome.values().length];

    void add(RecoveryOutcome outcome) {
      processed++;
      outcomes[outcome.ordinal()]++;
    }

    RecoverySummary summary() {
      return new RecoverySummary(
          processed,
          outcomes[0],
          outcomes[1],
          outcomes[2],
          outcomes[3],
          outcomes[4],
          outcomes[5],
          outcomes[6]);
    }
  }
}
