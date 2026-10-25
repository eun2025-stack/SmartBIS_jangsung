package com.smartbis.backend.display;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DisplayControllerTest {
    @Test
    void returnsNoContentWhenThereIsNothingToDisplay() {
        DisplayService displayService = mock(DisplayService.class);
        when(displayService.next("BUS-001"))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        var response = new DisplayController(displayService).next("BUS-001");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
    }

    @Test
    void keepsUnexpectedServiceErrorsAsErrors() {
        DisplayService displayService = mock(DisplayService.class);
        when(displayService.next("BUS-001"))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

        var exception = org.junit.jupiter.api.Assertions.assertThrows(
                ResponseStatusException.class,
                () -> new DisplayController(displayService).next("BUS-001"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }
}
