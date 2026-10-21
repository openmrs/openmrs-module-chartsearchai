/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 */
package org.openmrs.module.chartsearchai.api.provider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import org.openmrs.Location;
import org.openmrs.Role;
import org.openmrs.User;
import org.openmrs.api.context.UserContext;

/**
 * Immutable account metadata captured on the authenticated request thread. This is context,
 * not an authorization decision or a selected occupational role. No OpenMRS entity or session
 * object is retained, and personal names, usernames, credentials and user properties are omitted.
 */
public final class AccountContext {

	private static final AccountContext UNAVAILABLE = new AccountContext(null,
			Collections.emptyList(), Collections.emptyList(), null, null);

	private final String userUuid;

	private final List<String> assignedRoles;

	private final List<String> effectiveRoles;

	private final Map<String, String> sessionLocation;

	private final String locale;

	private AccountContext(String userUuid, List<String> assignedRoles, List<String> effectiveRoles,
			Map<String, String> sessionLocation, String locale) {
		this.userUuid = userUuid;
		this.assignedRoles = Collections.unmodifiableList(new ArrayList<>(assignedRoles));
		this.effectiveRoles = Collections.unmodifiableList(new ArrayList<>(effectiveRoles));
		this.sessionLocation = sessionLocation == null ? null
				: Collections.unmodifiableMap(new LinkedHashMap<>(sessionLocation));
		this.locale = locale;
	}

	public static AccountContext unavailable() {
		return UNAVAILABLE;
	}

	/** Capture before dispatch; callers must not pass UserContext to provider worker threads. */
	public static AccountContext fromSession(UserContext session) {
		User user = session == null ? null : session.getAuthenticatedUser();
		if (user == null) {
			return unavailable();
		}
		Location location = session.getLocation();
		Map<String, String> locationData = null;
		if (location != null) {
			locationData = new LinkedHashMap<>();
			locationData.put("uuid", location.getUuid());
			locationData.put("name", location.getName());
		}
		Locale locale = session.getLocale();
		try {
			return new AccountContext(user.getUuid(), roleNames(user.getRoles()),
					roleNames(session.getAllRoles()), locationData, locale == null ? null : locale.toLanguageTag());
		}
		catch (Exception e) {
			throw new IllegalStateException("Could not capture authenticated account roles", e);
		}
	}

	private static List<String> roleNames(Collection<Role> roles) {
		TreeSet<String> names = new TreeSet<>();
		if (roles == null) {
			return Collections.emptyList();
		}
		for (Role role : roles) {
			if (role != null && role.getRole() != null) {
				names.add(role.getRole());
			}
		}
		return new ArrayList<>(names);
	}

	public String getUserUuid() {
		return userUuid;
	}

	public List<String> getAssignedRoles() {
		return assignedRoles;
	}

	/** Includes assigned/inherited roles and OpenMRS's implicit session roles. */
	public List<String> getEffectiveRoles() {
		return effectiveRoles;
	}

	public Map<String, String> getSessionLocation() {
		return sessionLocation;
	}

	public String getLocale() {
		return locale;
	}

	/**
	 * For the access-controlled integration request and turn audit, not a system prompt.
	 * A remote recipient must authenticate its caller; the source label alone proves no trust.
	 */
	public Map<String, Object> toPayload() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("source", this == UNAVAILABLE ? "unavailable" : "openmrs_session");
		payload.put("user_uuid", userUuid);
		payload.put("assigned_roles", assignedRoles);
		payload.put("effective_roles", effectiveRoles);
		payload.put("session_location", sessionLocation);
		payload.put("locale", locale);
		return Collections.unmodifiableMap(payload);
	}
}
