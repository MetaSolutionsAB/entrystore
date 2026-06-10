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

package org.entrystore.rest.springboot.security;

import jakarta.servlet.http.HttpServletRequest;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.springboot.configuration.CasCustomConfiguration;
import org.springframework.security.cas.authentication.CasAuthenticationToken;

public class CasLoginSuccessHandler extends AbstractSsoLoginSuccessHandler<CasAuthenticationToken, Void> {

	private final CasCustomConfiguration casConfiguration;

	public CasLoginSuccessHandler(ESUserDetailsService userService, PrincipalManager principalManager,
								  CasCustomConfiguration casConfiguration) {
		super(userService, principalManager);
		this.casConfiguration = casConfiguration;
	}

	@Override
	protected String authTypeLabel() {
		return "CAS";
	}

	@Override
	protected Class<CasAuthenticationToken> tokenType() {
		return CasAuthenticationToken.class;
	}

	@Override
	protected Void resolveContext(HttpServletRequest request, CasAuthenticationToken token) {
		return null;
	}

	@Override
	protected boolean isAutoProvisioningEnabled(Void context) {
		return casConfiguration.userAutoProvisioning();
	}

	@Override
	protected String defaultFailureUrl() {
		return casConfiguration.redirectFailure().url();
	}
}
