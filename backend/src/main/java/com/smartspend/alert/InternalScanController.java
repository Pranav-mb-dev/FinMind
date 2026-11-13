package com.smartspend.alert;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Called by the nightly n8n workflow. Guarded by the X-Service-Token filter (see SecurityConfig). */
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalScanController {

    private final AlertService alertService;

    @PostMapping("/recurring/scan")
    public ResponseEntity<Map<String, Integer>> scan() {
        return ResponseEntity.ok(Map.of("alertsCreated", alertService.scanAllUsers()));
    }
}
