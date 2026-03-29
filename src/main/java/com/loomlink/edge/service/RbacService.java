package com.loomlink.edge.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Role-Based Access Control (RBAC) mock for the Stavanger demo.
 *
 * <p>Demonstrates that the Exception Inbox enforces role-based authorization:
 * only users with the SENIOR_ENGINEER role can approve, reclassify, or dismiss
 * rejected classifications. Operators and Technicians can VIEW but not ACT.</p>
 *
 * <p>In production, this would integrate with:</p>
 * <ul>
 *   <li>SAP IDM (Identity Management) for role mapping</li>
 *   <li>Active Directory / Azure AD via SAML2 or OIDC</li>
 *   <li>Spring Security with {@code @PreAuthorize("hasRole('SENIOR_ENGINEER')")}</li>
 * </ul>
 *
 * <p>For the demo, roles are assigned by name and stored in-memory. The dashboard
 * sends the engineer name in API calls, and RBAC validates it against the role map.</p>
 */
@Service
public class RbacService {

    private static final Logger log = LoggerFactory.getLogger(RbacService.class);

    /**
     * Available roles in the system.
     */
    public enum Role {
        /** Can approve, reclassify, dismiss exceptions. Full pipeline access. */
        SENIOR_ENGINEER,
        /** Can view all data and run pipeline tests. Cannot modify exceptions. */
        OPERATOR,
        /** Can submit notifications. Read-only on dashboards. */
        TECHNICIAN,
        /** Full system access including configuration. */
        ADMIN
    }

    /** Actions that require specific roles. */
    public enum Action {
        VIEW_EXCEPTIONS,
        APPROVE_EXCEPTION,
        RECLASSIFY_EXCEPTION,
        DISMISS_EXCEPTION,
        RUN_PIPELINE,
        VIEW_DASHBOARD,
        TOGGLE_DEMO_MODE,
        VIEW_AUDIT_LOG
    }

    /** Role → permitted actions mapping. */
    private static final Map<Role, Set<Action>> ROLE_PERMISSIONS = Map.of(
            Role.SENIOR_ENGINEER, Set.of(
                    Action.VIEW_EXCEPTIONS, Action.APPROVE_EXCEPTION,
                    Action.RECLASSIFY_EXCEPTION, Action.DISMISS_EXCEPTION,
                    Action.RUN_PIPELINE, Action.VIEW_DASHBOARD,
                    Action.VIEW_AUDIT_LOG),
            Role.OPERATOR, Set.of(
                    Action.VIEW_EXCEPTIONS, Action.RUN_PIPELINE,
                    Action.VIEW_DASHBOARD, Action.VIEW_AUDIT_LOG),
            Role.TECHNICIAN, Set.of(
                    Action.RUN_PIPELINE, Action.VIEW_DASHBOARD),
            Role.ADMIN, Set.of(Action.values())
    );

    /** In-memory user → role map. Pre-seeded with demo users. */
    private final ConcurrentHashMap<String, Role> userRoles = new ConcurrentHashMap<>(Map.of(
            "Senior Engineer", Role.SENIOR_ENGINEER,
            "Lars Hansen", Role.SENIOR_ENGINEER,
            "Ingrid Johansen", Role.SENIOR_ENGINEER,
            "Operator Sven", Role.OPERATOR,
            "Technician Ole", Role.TECHNICIAN,
            "Admin", Role.ADMIN
    ));

    /**
     * Check if a user is authorized to perform an action.
     *
     * @param userName the name from the API request
     * @param action   the action being attempted
     * @return true if authorized
     */
    public boolean isAuthorized(String userName, Action action) {
        if (userName == null || userName.isBlank()) {
            log.warn("RBAC: empty user name, denying access for action {}", action);
            return false;
        }

        Role role = userRoles.get(userName);

        // Unknown users default to OPERATOR (view-only, no approve/reclassify/dismiss)
        if (role == null) {
            log.info("RBAC: unknown user '{}', assigning default role OPERATOR (view-only)", userName);
            role = Role.OPERATOR;
            userRoles.put(userName, role);
        }

        Set<Action> permitted = ROLE_PERMISSIONS.get(role);
        boolean authorized = permitted != null && permitted.contains(action);

        if (!authorized) {
            log.warn("RBAC DENIED: user '{}' (role: {}) attempted action {} — insufficient permissions",
                    userName, role, action);
        } else {
            log.debug("RBAC: user '{}' (role: {}) authorized for action {}", userName, role, action);
        }

        return authorized;
    }

    /**
     * Get the role for a user. Returns null if unknown.
     */
    public Role getRole(String userName) {
        return userRoles.get(userName);
    }

    /**
     * Assign a role to a user (for demo configuration).
     */
    public void assignRole(String userName, Role role) {
        userRoles.put(userName, role);
        log.info("RBAC: assigned role {} to user '{}'", role, userName);
    }

    /**
     * Get all registered users and their roles (for dashboard display).
     */
    public Map<String, Role> getAllUsers() {
        return Map.copyOf(userRoles);
    }
}
