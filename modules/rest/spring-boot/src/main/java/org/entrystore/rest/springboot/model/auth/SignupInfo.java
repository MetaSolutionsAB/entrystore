/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.springboot.model.auth;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;

import java.util.Date;
import java.util.Map;

/**
 * @author Hannes Ebner
 */
@Getter
@Setter
public class SignupInfo {

	private String firstName;

	private String lastName;

	private String email;

	private String saltedHashedPassword;

	private Date expirationDate;

	private String urlSuccess;

	private String urlFailure;

	private Map<String, String> customProperties;

	/**
	 * Number of failed credential-confirmation attempts made against this pending token. Once it
	 * reaches the configured limit the token is invalidated (see {@code SignupTokenCache#confirmAttempt}).
	 * This counter is the sole enforcement of the confirmation lockout, so it has no public setter and is
	 * mutated only via {@link #recordFailedConfirmation()}, keeping its monotonic increment owned here.
	 */
	@Setter(AccessLevel.NONE)
	private int confirmationAttempts;

	/**
	 * Records a failed credential-confirmation attempt by incrementing the counter and returning the new total.
	 */
	public int recordFailedConfirmation() {
		return ++confirmationAttempts;
	}

	/**
	 * Estimated bytes held in this record's strings, for the token cache's byte bound: one byte per char for a
	 * Latin-1 string, two once it holds a char above U+00FF (compact strings). A new string field must be added
	 * here, or it escapes the bound.
	 */
	public int estimatedSize() {
		int size = bytes(firstName) + bytes(lastName) + bytes(email) + bytes(saltedHashedPassword)
				+ bytes(urlSuccess) + bytes(urlFailure);
		if (customProperties != null) {
			for (var property : customProperties.entrySet()) {
				size += bytes(property.getKey()) + bytes(property.getValue());
			}
		}
		return size;
	}

	private static int bytes(String value) {
		if (value == null) {
			return 0;
		}
		return value.chars().allMatch(c -> c <= 0xFF) ? value.length() : 2 * value.length();
	}
	public void setEmail(@NonNull String email) {
		// we have to store it in lower case only to avoid problems with different cases in
		// different steps of the process (if the user provides inconsistent information)
		this.email = email.toLowerCase();
	}

}
