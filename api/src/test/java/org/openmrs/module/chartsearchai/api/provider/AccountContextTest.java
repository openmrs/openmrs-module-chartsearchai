/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 */
package org.openmrs.module.chartsearchai.api.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.openmrs.Location;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.context.UserContext;

public class AccountContextTest {

	@Test
	public void snapshotKeepsAllAssignedAndInheritedRolesWithoutSelectingOne() {
		User user = user();
		Role access = new Role("Application: Uses ChartSearchAI (Research)");
		Role nurse = new Role("Organizational: Nurse");
		nurse.setInheritedRoles(Collections.singleton(access));
		user.addRole(nurse);
		user.addRole(new Role("Organizational: Peer Educator"));
		Location location = location();
		AccountContext context = AccountContext.fromSession(session(user, location));

		assertEquals("account-1", context.getUserUuid());
		assertEquals(Arrays.asList("Organizational: Nurse", "Organizational: Peer Educator"),
				context.getAssignedRoles());
		assertEquals(Arrays.asList("Anonymous", "Application: Uses ChartSearchAI (Research)",
				"Authenticated", "Organizational: Nurse", "Organizational: Peer Educator"),
				context.getEffectiveRoles());
		assertEquals("en-KE", context.getLocale());
		assertEquals("location-1", context.getSessionLocation().get("uuid"));
		assertEquals("Outpatient", context.getSessionLocation().get("name"));
		assertFalse(context.toPayload().containsKey("active_role"));
	}

	@Test
	public void snapshotDoesNotRetainMutableOpenmrsEntitiesOrExposePersonalDetails() {
		User user = user();
		Role role = new Role("Organizational: Nurse");
		user.addRole(role);
		Location location = location();
		AccountContext context = AccountContext.fromSession(session(user, location));
		user.setUuid("different-user");
		role.setRole("Organizational: Doctor");
		location.setName("Different clinic");
		user.getRoles().clear();

		assertEquals("account-1", context.getUserUuid());
		assertEquals(Collections.singletonList("Organizational: Nurse"), context.getAssignedRoles());
		assertEquals("Outpatient", context.getSessionLocation().get("name"));
		assertThrows(UnsupportedOperationException.class, () -> context.getAssignedRoles().clear());
		assertThrows(UnsupportedOperationException.class, () -> context.getEffectiveRoles().clear());
		assertThrows(UnsupportedOperationException.class, () -> context.getSessionLocation().clear());
		assertThrows(UnsupportedOperationException.class, () -> context.toPayload().clear());
		assertFalse(context.toPayload().toString().contains("private-username"));
		assertFalse(context.toPayload().toString().contains("private-property"));
	}

	@Test
	public void missingLocationStaysUnknown() {
		AccountContext context = AccountContext.fromSession(session(user(), null));
		assertNull(context.getSessionLocation());
		assertNull(context.toPayload().get("session_location"));
		assertEquals("openmrs_session", context.toPayload().get("source"));
	}

	@Test
	public void missingAuthenticationDoesNotClaimAuthenticatedContext() {
		assertEquals("unavailable", AccountContext.fromSession(null).toPayload().get("source"));
		Map<String, Object> payload = AccountContext.fromSession(new UserContext(null)).toPayload();
		assertEquals("unavailable", payload.get("source"));
		assertNull(payload.get("user_uuid"));
		assertEquals(Collections.emptyList(), payload.get("effective_roles"));
	}

	@Test
	public void subsequentCaptureReflectsRoleAndLocationChangesWithoutChangingPriorTurn() {
		User user = user();
		user.addRole(new Role("Organizational: Nurse"));
		AccountContext before = AccountContext.fromSession(session(user, location()));
		user.getRoles().clear();
		user.addRole(new Role("Organizational: Doctor"));
		AccountContext after = AccountContext.fromSession(session(user, null));
		assertEquals(Collections.singletonList("Organizational: Nurse"), before.getAssignedRoles());
		assertEquals(Collections.singletonList("Organizational: Doctor"), after.getAssignedRoles());
		assertNull(after.getSessionLocation());
	}

	@Test
	public void roleReadFailureCannotMasqueradeAsAnAccountWithNoRoles() {
		User user = user();
		IllegalStateException error = assertThrows(IllegalStateException.class,
				() -> AccountContext.fromSession(new UserContext(null) {
					@Override
					public User getAuthenticatedUser() {
						return user;
					}

					@Override
					public Set<Role> getAllRoles() throws Exception {
						throw new Exception("Role storage unavailable");
					}

					@Override
					public Locale getLocale() {
						return Locale.ENGLISH;
					}
				}));
		assertEquals("Could not capture authenticated account roles", error.getMessage());
	}

	private static User user() {
		User user = new User(1);
		user.setUuid("account-1");
		user.setUsername("private-username");
		user.setUserProperty("private-property", "not-for-the-model");
		return user;
	}

	private static Location location() {
		Location location = new Location(1);
		location.setUuid("location-1");
		location.setName("Outpatient");
		return location;
	}

	private static UserContext session(User user, Location location) {
		return new UserContext(null) {
			@Override
			public User getAuthenticatedUser() {
				return user;
			}

			@Override
			public Set<Role> getAllRoles() {
				Set<Role> roles = new HashSet<>(user.getAllRoles());
				roles.add(new Role("Authenticated"));
				roles.add(new Role("Anonymous"));
				return roles;
			}

			@Override
			public Location getLocation() {
				return location;
			}

			@Override
			public Locale getLocale() {
				return Locale.forLanguageTag("en-KE");
			}
		};
	}
}
