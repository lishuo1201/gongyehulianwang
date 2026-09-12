package com.example.agv.simulator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        useMainMethod = SpringBootTest.UseMainMethod.ALWAYS)
class SimulatorApplicationTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void startsApplicationContext() {
        assertNotNull(context.getBean(SimulatorApplication.class));
    }
}
