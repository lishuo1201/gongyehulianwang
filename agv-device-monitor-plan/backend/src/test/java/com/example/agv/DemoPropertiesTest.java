package com.example.agv;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DemoPropertiesTest {

    @Test
    void acceptsAnExplicitlyAllowedLocalTarget() {
        var properties = new DemoProperties("127.0.0.1", 1502, List.of("127.0.0.1"), List.of(1502), 10);
        assertEquals("127.0.0.1", properties.seedHost());
    }

    @Test
    void rejectsMissingOrUnapprovedTargetsAndInvalidLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new DemoProperties(null, 1502, List.of("simulator"), List.of(1502), 10));
        assertThrows(IllegalArgumentException.class,
                () -> new DemoProperties("other-host", 1502, List.of("simulator"), List.of(1502), 10));
        assertThrows(IllegalArgumentException.class,
                () -> new DemoProperties("simulator", 1503, List.of("simulator"), List.of(1502), 10));
        assertThrows(IllegalArgumentException.class,
                () -> new DemoProperties("simulator", 65536, List.of("simulator"), List.of(65536), 10));
        assertThrows(IllegalArgumentException.class,
                () -> new DemoProperties("simulator", 1502, List.of("simulator"), List.of(1502), 0));
    }
}
