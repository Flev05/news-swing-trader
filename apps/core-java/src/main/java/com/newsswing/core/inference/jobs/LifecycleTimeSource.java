package com.newsswing.core.inference.jobs;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

/** Separate from ingestion's Clock; the caller supplies its transaction-bound connection. */
interface LifecycleTimeSource {
  Instant now(Connection transactionConnection) throws SQLException;
}
