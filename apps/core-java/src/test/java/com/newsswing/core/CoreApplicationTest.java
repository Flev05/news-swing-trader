package com.newsswing.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CoreApplicationTest {

  @Test
  void exposesTheCoreApplicationType() {
    assertEquals("CoreApplication", CoreApplication.class.getSimpleName());
  }
}
