package com.newsswing.core.news.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

/** Enforces the ingestion JSON body limit against raw bytes before MVC binding. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class NewsRequestSizeFilter extends OncePerRequestFilter {

  private static final String INGEST_PATH = "/api/v1/news-events";

  private final int maxRequestBytes;
  private final JsonMapper jsonMapper;

  NewsRequestSizeFilter(NewsApiProperties properties, JsonMapper jsonMapper) {
    this.maxRequestBytes = properties.maxRequestBytes();
    this.jsonMapper = jsonMapper;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !"POST".equals(request.getMethod())
        || !INGEST_PATH.equals(request.getRequestURI().substring(request.getContextPath().length()))
        || !isApplicationJson(request.getContentType());
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    if (request.getContentLengthLong() > maxRequestBytes) {
      writePayloadTooLarge(request, response);
      return;
    }

    byte[] body = request.getInputStream().readNBytes(maxRequestBytes + 1);
    if (body.length > maxRequestBytes) {
      writePayloadTooLarge(request, response);
      return;
    }

    filterChain.doFilter(new CachedBodyRequest(request, body), response);
  }

  private void writePayloadTooLarge(HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    ProblemSpec spec =
        new ProblemSpec(
            org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE,
            "PAYLOAD_TOO_LARGE",
            "Payload too large",
            "The JSON request body exceeds the configured byte limit.");
    response.setStatus(spec.status().value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader("Cache-Control", "no-store");
    jsonMapper.writeValue(
        response.getOutputStream(), NewsApiProblemFactory.problem(spec, request.getRequestURI()));
  }

  private static boolean isApplicationJson(String contentType) {
    return contentType != null
        && contentType.regionMatches(true, 0, MediaType.APPLICATION_JSON_VALUE, 0, 16)
        && (contentType.length() == 16
            || contentType.charAt(16) == ';'
            || Character.isWhitespace(contentType.charAt(16)));
  }

  private static final class CachedBodyRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    private CachedBodyRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      return new CachedServletInputStream(body);
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
      return new BufferedReader(new InputStreamReader(getInputStream(), charset));
    }
  }

  private static final class CachedServletInputStream extends ServletInputStream {

    private final ByteArrayInputStream input;

    private CachedServletInputStream(byte[] body) {
      input = new ByteArrayInputStream(body);
    }

    @Override
    public int read() {
      return input.read();
    }

    @Override
    public boolean isFinished() {
      return input.available() == 0;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public void setReadListener(ReadListener readListener) {
      throw new IllegalStateException("Non-blocking reads are not supported");
    }
  }
}
