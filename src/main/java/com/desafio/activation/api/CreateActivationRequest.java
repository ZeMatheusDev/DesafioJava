package com.desafio.activation.api;

import com.desafio.activation.application.NewActivationRequest;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateActivationRequest(
        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = "deve conter apenas letras, números, '.', '_' ou '-'")
        String externalId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = "deve conter apenas letras, números, '.', '_' ou '-'")
        String customerId,

        @NotBlank(message = "é obrigatório")
        @Size(max = 64, message = "deve ter no máximo 64 caracteres")
        @Pattern(regexp = IDENTIFIER, message = "deve conter apenas letras, números, '.', '_' ou '-'")
        String productCode) {

    static final String IDENTIFIER = "^[A-Za-z0-9._-]*$";

    NewActivationRequest toCommand() {
        return new NewActivationRequest(externalId, customerId, productCode);
    }
}
