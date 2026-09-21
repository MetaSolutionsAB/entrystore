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

package org.entrystore.rest.springboot.service.auth;

import com.google.common.net.InetAddresses;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.Inet6Address;

/**
 * Applies Jakarta email syntax validation and EntryStore's domain policy: a dotted domain with a
 * suffix of at least two characters, or a bracketed IP literal. No TLD allowlist or DNS lookup is used.
 * IPv6 literals must carry the RFC 5321 {@code IPv6:} tag (matched case-insensitively); a bare bracketed
 * IPv6 address is rejected, as is a zone ID such as {@code %eth0}.
 *
 * <p>Reached declaratively through
 * {@link org.entrystore.rest.springboot.model.validation.ValidEmail} on request bodies, and directly
 * by {@code AuthService} on the password-reset path.
 */
@Service
@RequiredArgsConstructor
public class EmailValidator {

	private final Validator validator;

	/**
	 * Rejects absent values for direct callers. The declarative {@code @ValidEmail} constraint leaves
	 * null and empty values to the request body's presence constraint.
	 */
	public boolean isValid(String email) {
		if (email == null || email.isEmpty()) {
			return false;
		}
		if (!validator.validate(new Address(email)).isEmpty()) {
			return false;
		}

		String domain = email.substring(email.lastIndexOf('@') + 1);
		if (domain.startsWith("[") && domain.endsWith("]")) {
			String literal = domain.substring(1, domain.length() - 1);
			// A zone ID names a host-local scope, which the policy rejects just like "localhost".
			if (literal.indexOf('%') >= 0) {
				return false;
			}
			if (literal.regionMatches(true, 0, "IPv6:", 0, 5)) {
				literal = literal.substring(5);
				return InetAddresses.isInetAddress(literal)
						&& InetAddresses.forString(literal) instanceof Inet6Address;
			}
			// Hibernate's @Email checks IPv4 shape but does not enforce the octet range.
			return InetAddresses.isInetAddress(literal);
		}

		int lastDot = domain.lastIndexOf('.');
		int suffixLength = domain.length() - lastDot - 1;
		return lastDot > 0 && suffixLength >= 2;
	}

	private record Address(@Email String value) {
	}
}
