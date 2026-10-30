package com.desafio.activation.api;

import com.desafio.activation.application.ActivationRequestService;
import com.desafio.activation.domain.RequestStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/requests")
public class ActivationRequestController {

    private final ActivationRequestService service;

    public ActivationRequestController(ActivationRequestService service) {
        this.service = service;
    }

    /**
     * 201 + Location quando cria; 200 com o registro existente quando é um retry com os mesmos dados;
     * 409 quando o externalId já existe com dados diferentes.
     */
    @PostMapping
    public ResponseEntity<ActivationRequestResponse> create(@Valid @RequestBody CreateActivationRequest body,
                                                            @AuthenticationPrincipal Jwt caller) {
        var result = service.create(body.toCommand(), caller.getSubject());
        var response = ActivationRequestResponse.from(result.request());
        if (!result.created()) {
            return ResponseEntity.ok(response);
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(response.id())
                .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping("/{id}")
    public ActivationRequestResponse get(@PathVariable UUID id, @AuthenticationPrincipal Jwt caller) {
        return ActivationRequestResponse.from(service.get(id, caller.getSubject()));
    }

    @GetMapping
    public PageResponse<ActivationRequestResponse> search(
            @RequestParam(required = false) RequestStatus status,
            @RequestParam(required = false) String customerId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal Jwt caller) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.from(service.search(caller.getSubject(), status, customerId, pageable),
                ActivationRequestResponse::from);
    }
}
