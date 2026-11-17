package com.erlan.testtask.controller;

import com.erlan.testtask.service.ExternalApiService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/external/objects")
@RequiredArgsConstructor
public class ExternalApiController {

    private final ExternalApiService service;

    @GetMapping
    public JsonNode getObjects() {
        return service.getObjects();
    }
}
