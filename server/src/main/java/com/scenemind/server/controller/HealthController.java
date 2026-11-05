package com.scenemind.server.controller;

import com.scenemind.server.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public Result<String> health() {
        return Result.ok("UP");
    }
}
