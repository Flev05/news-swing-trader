package com.newsswing.core.inference.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;
import tools.jackson.databind.json.JsonMapper;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class WorkerRequestFilter extends OncePerRequestFilter {
  static final String PREFIX = "/api/v1/worker/";
  static final String BODY = WorkerRequestFilter.class.getName() + ".body";
  static final int CONTROL_LIMIT = 8192;
  static final int COMPLETION_LIMIT = 1048576;
  private static final Set<String> OPERATIONS = Set.of("claim", "renew", "fail", "complete");
  private final JsonMapper mapper;

  WorkerRequestFilter(JsonMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !UrlPathHelper.defaultInstance.getPathWithinApplication(request).startsWith(PREFIX);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    response.setHeader("Cache-Control", "no-store");
    String path = UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    String operation = path.substring(PREFIX.length());
    if (!"POST".equals(request.getMethod()) || !OPERATIONS.contains(operation)) {
      chain.doFilter(request, response);
      return;
    }
    try {
      int limit = operation.equals("complete") ? COMPLETION_LIMIT : CONTROL_LIMIT;
      if (request.getContentLengthLong() > limit)
        throw new WorkerApiException(413, "PAYLOAD_TOO_LARGE");
      byte[] bytes = request.getInputStream().readNBytes(limit + 1);
      if (bytes.length > limit) throw new WorkerApiException(413, "PAYLOAD_TOO_LARGE");
      String encoding = request.getHeader("Content-Encoding");
      if (encoding != null && !encoding.equalsIgnoreCase("identity"))
        throw new WorkerApiException(415, "UNSUPPORTED_CONTENT_ENCODING");
      try {
        var media =
            MediaType.parseMediaType(
                request.getContentType() == null ? "" : request.getContentType());
        if (!media.getType().equalsIgnoreCase("application")
            || !media.getSubtype().equalsIgnoreCase("json")
            || (media.getCharset() != null && !media.getCharset().equals(StandardCharsets.UTF_8)))
          throw new IllegalArgumentException();
      } catch (IllegalArgumentException exception) {
        throw new WorkerApiException(415, "UNSUPPORTED_MEDIA_TYPE");
      }
      if (request.getQueryString() != null) throw new WorkerApiException(400, "INVALID_COMMAND");
      request.setAttribute(BODY, bytes);
    } catch (WorkerApiException exception) {
      response.setStatus(exception.status);
      response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
      if (exception.status == 413) response.setHeader("Connection", "close");
      mapper.writeValue(
          response.getOutputStream(),
          WorkerProblems.problem(exception.status, exception.code, path));
      return;
    }
    chain.doFilter(request, response);
  }
}
