package com.smartbis.backend.arrival;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/v1/arrival")
public class ArrivalController {
    private final ArrivalService arrivalService;

    public ArrivalController(ArrivalService arrivalService) {
        this.arrivalService = arrivalService;
    }

    @GetMapping
    public ArrivalPrediction predict(
            @RequestParam String vehicleNumber,
            @RequestParam(required=false) String afterPassedNodeId
    ) {
        return afterPassedNodeId==null?arrivalService.predict(vehicleNumber):arrivalService.predict(vehicleNumber,afterPassedNodeId);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> unavailable(ResponseStatusException error) {
        if (error.getStatusCode().value()!=404) throw error;
        return ResponseEntity.status(404).body(Map.of("message","정보 없음"));
    }
}
