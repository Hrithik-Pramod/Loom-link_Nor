package com.loomlink.edge.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the RBAC Service — role-based access control enforcement.
 */
@DisplayName("RBAC Service - Access Control Tests")
class RbacServiceTest {

    private RbacService rbac;

    @BeforeEach
    void setUp() {
        rbac = new RbacService();
    }

    @Test
    @DisplayName("Senior Engineer can approve exceptions")
    void seniorEngineerCanApprove() {
        assertTrue(rbac.isAuthorized("Lars Hansen", RbacService.Action.APPROVE_EXCEPTION));
    }

    @Test
    @DisplayName("Senior Engineer can reclassify exceptions")
    void seniorEngineerCanReclassify() {
        assertTrue(rbac.isAuthorized("Lars Hansen", RbacService.Action.RECLASSIFY_EXCEPTION));
    }

    @Test
    @DisplayName("Operator cannot approve exceptions")
    void operatorCannotApprove() {
        assertFalse(rbac.isAuthorized("Operator Sven", RbacService.Action.APPROVE_EXCEPTION));
    }

    @Test
    @DisplayName("Technician cannot dismiss exceptions")
    void technicianCannotDismiss() {
        assertFalse(rbac.isAuthorized("Technician Ole", RbacService.Action.DISMISS_EXCEPTION));
    }

    @Test
    @DisplayName("Operator can view dashboard")
    void operatorCanViewDashboard() {
        assertTrue(rbac.isAuthorized("Operator Sven", RbacService.Action.VIEW_DASHBOARD));
    }

    @Test
    @DisplayName("Admin can toggle demo mode")
    void adminCanToggleDemo() {
        assertTrue(rbac.isAuthorized("Admin", RbacService.Action.TOGGLE_DEMO_MODE));
    }

    @Test
    @DisplayName("Unknown user defaults to OPERATOR — cannot approve exceptions")
    void unknownUserDefaultsToOperator() {
        assertFalse(rbac.isAuthorized("Unknown Person", RbacService.Action.APPROVE_EXCEPTION));
        assertEquals(RbacService.Role.OPERATOR, rbac.getRole("Unknown Person"));
    }

    @Test
    @DisplayName("Unknown user with OPERATOR role can still view dashboard")
    void unknownUserCanViewDashboard() {
        assertTrue(rbac.isAuthorized("Random Visitor", RbacService.Action.VIEW_DASHBOARD));
    }

    @Test
    @DisplayName("Empty username is denied")
    void emptyUserDenied() {
        assertFalse(rbac.isAuthorized("", RbacService.Action.VIEW_DASHBOARD));
        assertFalse(rbac.isAuthorized(null, RbacService.Action.APPROVE_EXCEPTION));
    }

    @Test
    @DisplayName("All known users are registered")
    void allUsersRegistered() {
        var users = rbac.getAllUsers();
        assertTrue(users.size() >= 5, "Should have at least 5 pre-seeded users");
        assertEquals(RbacService.Role.SENIOR_ENGINEER, users.get("Lars Hansen"));
        assertEquals(RbacService.Role.OPERATOR, users.get("Operator Sven"));
        assertEquals(RbacService.Role.TECHNICIAN, users.get("Technician Ole"));
        assertEquals(RbacService.Role.ADMIN, users.get("Admin"));
    }

    @Test
    @DisplayName("Role assignment works correctly")
    void roleAssignment() {
        rbac.assignRole("New Engineer", RbacService.Role.SENIOR_ENGINEER);
        assertTrue(rbac.isAuthorized("New Engineer", RbacService.Action.APPROVE_EXCEPTION));

        rbac.assignRole("New Engineer", RbacService.Role.TECHNICIAN);
        assertFalse(rbac.isAuthorized("New Engineer", RbacService.Action.APPROVE_EXCEPTION));
    }

    @Test
    @DisplayName("Technician can run pipeline but not view audit log")
    void technicianPermissions() {
        assertTrue(rbac.isAuthorized("Technician Ole", RbacService.Action.RUN_PIPELINE));
        assertFalse(rbac.isAuthorized("Technician Ole", RbacService.Action.VIEW_AUDIT_LOG));
    }
}
