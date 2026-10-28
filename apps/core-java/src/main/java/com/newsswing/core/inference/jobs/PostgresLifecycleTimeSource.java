package com.newsswing.core.inference.jobs;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

final class PostgresLifecycleTimeSource implements LifecycleTimeSource {
  @Override
  public Instant now(Connection transactionConnection) throws SQLException {
    try (var statement = transactionConnection.prepareStatement("SELECT clock_timestamp()");
        var result = statement.executeQuery()) {
      if (!result.next()) {
        throw new SQLException("PostgreSQL decision clock returned no row");
      }
      return result.getTimestamp(1).toInstant();
    }
  }
}
