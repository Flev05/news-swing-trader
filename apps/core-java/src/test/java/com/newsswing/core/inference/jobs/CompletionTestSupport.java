package com.newsswing.core.inference.jobs;

import static com.newsswing.core.inference.jobs.InferenceJobLifecycleService.*;
import static org.assertj.core.api.Assertions.*;

import com.newsswing.support.InferenceJobFixtures;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

abstract class CompletionTestSupport extends LifecycleTestSupport {
  static final JsonMapper JSON = JsonMapper.builder().build();

  static String payload(Assignment a) {
    return InferenceJobFixtures.resultPayload(
        a.newsEventJson(), a.inferenceId(), a.expectedBackend(), NOW);
  }

  static String change(String payload, String pointer, String value) {
    var document = (ObjectNode) JSON.readTree(payload);
    int slash = pointer.lastIndexOf('/');
    var parent = (ObjectNode) document.at(pointer.substring(0, slash));
    parent.put(pointer.substring(slash + 1), value);
    return document.toString();
  }

  static String incompatible(String payload, String value) {
    return payload.replace("explicit-test-fixture", value);
  }

  String resultSnapshot(UUID job) {
    return jdbc.query(
            "SELECT to_jsonb(r)::text FROM trader.inference_results r WHERE job_id = ?",
            (rs, n) -> rs.getString(1),
            job)
        .toString();
  }

  int resultCount(UUID job) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM trader.inference_results WHERE job_id = ?", Integer.class, job);
  }

  Connection blocker() throws SQLException {
    var connection = jdbc.getDataSource().getConnection();
    connection.setAutoCommit(false);
    execute(connection, "SET LOCAL statement_timeout = '5s'");
    return connection;
  }

  static void execute(Connection connection, String sql) throws SQLException {
    try (var statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  static final class Pool implements AutoCloseable {
    final ExecutorService executor = Executors.newFixedThreadPool(3);

    <T> Future<T> submit(Callable<T> task) {
      return executor.submit(task);
    }

    public void close() throws InterruptedException {
      executor.shutdownNow();
      assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }
  }
}
