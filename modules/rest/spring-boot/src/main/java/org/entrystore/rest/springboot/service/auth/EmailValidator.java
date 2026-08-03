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

import org.apache.commons.validator.routines.InetAddressValidator;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This email validator class replaces the Apache Commons Validator due to its
 * lack of support for flexible handling of new TLDs. This validator provides
 * less strict validation of domain names (it does not compare against a white * list),
 * but maintains a sufficient level of validation.
 *
 * <p>Reached declaratively through
 * {@link org.entrystore.rest.springboot.model.validation.ValidEmail} on request bodies, and directly
 * by {@code AuthService} on the password-reset path.
 */
@Service
public class EmailValidator extends org.apache.commons.validator.routines.EmailValidator {

	private static final Pattern IP_DOMAIN_PATTERN = Pattern.compile("^\\[(.*)]$");

	public EmailValidator() {
		super(true);
	}

	/**
	 * Hides the inherited Commons factory so a stale {@code getInstance()} call fails fast instead of
	 * silently returning the stricter Commons validator, whose TLD whitelist rejects the very addresses
	 * this class exists to accept. Inject the Spring bean instead.
	 *
	 * <p>Kept as a throwing method rather than a note in the javadoc: the inherited static stays
	 * reachable either way, so only code can stop it being called.
	 */
	public static EmailValidator getInstance() {
		throw new UnsupportedOperationException("Inject the EmailValidator Spring bean instead");
	}

	@Override
	protected boolean isValidDomain(String domain) {
		// see if domain is an IP address in brackets
		Matcher ipDomainMatcher = IP_DOMAIN_PATTERN.matcher(domain);

		if (ipDomainMatcher.matches()) {
			InetAddressValidator inetAddressValidator =
					InetAddressValidator.getInstance();
			return inetAddressValidator.isValid(ipDomainMatcher.group(1));
		} else {
			// We don't use Commons Validator's DomainValidator because it
			// has an outdated list of TLDs and does not support new ones
			// less than two characters for TLD
			return domain.contains(".") && // no dot
					domain.indexOf(".") != 0 && // dot in the beginning
					domain.lastIndexOf(".") <= domain.length() - 3;
		}
	}

}
