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

package org.entrystore.rest.it

import org.spockframework.runtime.extension.IGlobalExtension
import org.spockframework.runtime.model.SpecInfo

/**
 * Tells {@link KeycloakBaseSpec} whether the run includes one of its specs, so that {@link BaseSpec} starts Keycloak
 * in the background only for runs that need it. Spock visits every spec of a run at discovery, before the first one
 * runs, and Failsafe discovers all ITs at once. Discovered via
 * {@code META-INF/services/org.spockframework.runtime.extension.IGlobalExtension}.
 */
class KeycloakStartExtension implements IGlobalExtension {

	@Override
	void visitSpec(SpecInfo spec) {
		if (KeycloakBaseSpec.isAssignableFrom(spec.reflection)) {
			KeycloakBaseSpec.includedInRun = true
		}
	}
}
