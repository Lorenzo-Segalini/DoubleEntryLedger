package dev.lseg.ledger.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(@NotBlank @Size(max = 320) String email, @NotBlank @Size(max = 200) String password) {}
