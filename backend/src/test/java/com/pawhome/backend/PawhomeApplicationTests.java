package com.pawhome.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Context-load smoke test. It needs a reachable database, so start Docker
// first:  cd docker && docker compose up -d
//
// TODO: this is the ONLY test in the project. Cha Ghor shipped with exactly
// one test file across 285 Java files and it was the single biggest gap.
// Add real unit tests for pure calculation logic as soon as you have any.
@SpringBootTest
class PawhomeApplicationTests {

    @Test
    void contextLoads() {
    }
}
