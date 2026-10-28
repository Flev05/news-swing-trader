package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleRepository.*;
import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;

import com.newsswing.core.contracts.SharedContractValidator.Violation;
import com.newsswing.core.news.ingestion.NewsIngestionProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@Service
final class JdbcInferenceJobLifecycleService implements InferenceJobLifecycleService {
  private final InferenceJobLifecycleRepository repository;
  private final InferenceJobLifecycleProperties properties;
  private final int capacity;
  private final InferenceResultContractValidator resultValidator;
  private final TransactionTemplate resultReads;
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private final TransactionTemplate transactions;
  private final TransactionTemplate discoveryTransactions;

  JdbcInferenceJobLifecycleService(
      InferenceJobLifecycleRepository repository,
      InferenceJobLifecycleProperties properties,
      NewsIngestionProperties ingestion,
      PlatformTransactionManager manager,
      InferenceResultContractValidator resultValidator) {
    this.repository = repository;
    this.resultValidator = resultValidator;
    this.resultReads = new TransactionTemplate(manager);
    this.resultReads.setReadOnly(true);
    this.resultReads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
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
  public CompletionOutcome complete(AssignmentRef assignment, String resultJson) {
    rejectAmbientTransaction();
    if (!valid(assignment)) {
      return new Rejected(RejectionReason.INVALID_COMMAND);
    }
    var prepared = resultValidator.prepare(resultJson);
    return write(
        () -> {
          var locked = lockOwner(assignment);
          if (locked == null) {
            return new Rejected(RejectionReason.NOT_FOUND);
          }
          var job = locked.job();
          var accepted = repository.findAccepted(job.jobId());
          if ((accepted != null) != (job.status() == JobStatus.SUCCEEDED)) {
            throw new IllegalStateException("Accepted result and SUCCEEDED job must be paired");
          }
          if (accepted != null) {
            if (accepted.assignmentNumber() != assignment.assignmentNumber()
                || !accepted.token().equals(assignment.ownershipToken())) {
              return new Rejected(RejectionReason.OWNER_MISMATCH);
            }
            if (!prepared.valid()) {
              return new CompletionConflict();
            }
            return repository.payloadEquals(job.jobId(), prepared.json())
                ? new AlreadyAccepted(accepted.receipt())
                : new CompletionConflict();
          }
          // Fence authority before interpreting worker validation diagnostics.
          var staticRejection = ownershipRejection(assignment, locked);
          if (staticRejection != null) {
            return staticRejection;
          }
          var provenance =
              prepared.valid()
                  ? provenanceViolations(job, prepared.provenance())
                  : List.<Violation>of();
          Instant now = repository.now();
          var rejected = rejection(assignment, locked, now);
          if (rejected != null) {
            return rejected;
          }
          if (!prepared.valid() || !provenance.isEmpty()) {
            var code =
                prepared.valid() ? FailureCode.PROVENANCE_MISMATCH : FailureCode.INVALID_RESULT;
            repository.fail(job, code, now);
            return new InvalidResult(code, prepared.valid() ? provenance : prepared.violations());
          }
          repository.insertResult(job, prepared.json(), now);
          repository.succeed(job, now);
          return new Accepted(
              new CompletionReceipt(job.jobId(), job.revisionId(), job.inferenceId(), now));
        });
  }

  private static List<Violation> provenanceViolations(
      JobRow job, InferenceResultContractValidator.Provenance result) {
    var event = JSON.readTree(job.eventPayload());
    var expectedTickers =
        event
            .path("affected_symbols")
            .valueStream()
            .map(symbol -> symbol.asString())
            .collect(Collectors.toUnmodifiableSet());
    var violations = new ArrayList<Violation>();
    bind(violations, "/inference_id", job.inferenceId().equals(result.inferenceId()));
    bind(violations, "/news_event_id", job.revisionId().equals(result.eventId()));
    bind(violations, "/news_event_sha256", job.contentHash().equals(result.hash()));
    bind(
        violations,
        "/schema_version",
        "1.0.0".equals(result.schemaVersion())
            && "1.0.0".equals(job.inputSchemaVersion())
            && "1.0.0".equals(event.path("schema_version").asString()));
    bind(violations, "/model/backend", job.expectedBackend().equals(result.backend()));
    bind(violations, "/ticker_results", expectedTickers.equals(result.tickers()));
    return List.copyOf(violations);
  }

  private static void bind(List<Violation> violations, String path, boolean matches) {
    if (!matches) {
      violations.add(
          new Violation("provenance", path, "Result does not match stored job provenance"));
    }
  }

  @Override
  public Optional<CurrentEligibleResult> findCurrentEligibleResult(UUID newsItemId) {
    rejectAmbientTransaction();
    if (newsItemId == null) {
      throw new IllegalArgumentException("newsItemId is required");
    }
    return Objects.requireNonNull(
        resultReads.execute(
            ignored -> {
              repository.configureTransaction();
              repository.establishReadSnapshot();
              Instant now = repository.now();
              return repository.findCurrentEligibleResult(newsItemId, now);
            }));
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

  private static Rejected ownershipRejection(AssignmentRef ref, Locked locked) {
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
    return null;
  }

  private static Rejected rejection(AssignmentRef ref, Locked locked, Instant now) {
    var rejected = ownershipRejection(ref, locked);
    if (rejected != null) {
      return rejected;
    }
    var job = locked.job();
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
