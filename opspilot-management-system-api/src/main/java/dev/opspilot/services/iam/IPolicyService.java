package dev.opspilot.services.iam;

import dev.opspilot.security.authentication.OpsPilotPrincipal;

import java.util.Set;

/** Permission evaluation from active role assignments within the authenticated organization. */
public interface IPolicyService {

    boolean has(OpsPilotPrincipal actor, String permission);

    boolean hasOnUnit(OpsPilotPrincipal actor, String permission, long unitId);

    Set<Long> unitsWithPermission(OpsPilotPrincipal actor, String permission);
}
