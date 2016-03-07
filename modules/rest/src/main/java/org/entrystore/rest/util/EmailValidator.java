/*
 * Copyright (c) 2007-2016 MetaSolutions AB
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

package org.entrystore.rest.util;

import org.apache.commons.validator.routines.InetAddressValidator;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This email validator class replaces the Apache Commons Validator due to its
 * lack of support for flexible handling of new TLDs. This validator provides
 * less strict validation of domain names (it does not compare against a white
 * list), but maintains a sufficient level of validation.
 */
public class EmailValidator extends org.apache.commons.validator.routines.EmailValidator {

	private static final String IP_DOMAIN_REGEX = "^\\[(.*)\\]$";

	private static final Pattern IP_DOMAIN_PATTERN = Pattern.compile(IP_DOMAIN_REGEX);

	private static final EmailValidator EMAIL_VALIDATOR_WITH_LOCAL = new EmailValidator();

	protected EmailValidator() {
		super(true);
	}

	public static EmailValidator getInstance() {
		return EMAIL_VALIDATOR_WITH_LOCAL;
	}

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
			if (!domain.contains(".") || // no dot
					domain.indexOf(".") == 0 || // dot in the beginning
					domain.lastIndexOf(".") > domain.length() - 3) { // less than two characters for TLD
				return false;
			}
		}

		return true;
	}

}