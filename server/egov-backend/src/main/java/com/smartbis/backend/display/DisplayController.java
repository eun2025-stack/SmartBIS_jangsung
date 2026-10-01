package com.smartbis.backend.display;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/display")
public class DisplayController {
    private final DisplayService displayService;

    public DisplayController(DisplayService displayService) {
        this.displayService = displayService;
    }

    @GetMapping("/next")
    public DisplayItem next(
            @RequestParam String vehicleNumber
    ) {
        return displayService.next(vehicleNumber);
    }
}
