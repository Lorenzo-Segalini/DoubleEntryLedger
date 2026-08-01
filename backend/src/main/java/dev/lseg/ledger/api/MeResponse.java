package dev.lseg.ledger.api;

import java.util.List;
import java.util.UUID;

public record MeResponse(UUID id, String email, String displayName, String role, List<String> can) {}
