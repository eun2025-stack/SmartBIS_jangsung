package com.smartbis.backend.arrival;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/arrival")
public class ArrivalController {
    private final ArrivalService arrivalService;

    public ArrivalController(ArrivalService arrivalService) {
        this.arrivalService = arrivalService;
    }

    @GetMapping
    public ArrivalPrediction predict(
            @RequestParam String vehicleNumber
    ) {
        return arrivalService.predict(vehicleNumber);
    }
}
