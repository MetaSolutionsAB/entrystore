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

import dasniko.testcontainers.keycloak.KeycloakContainer
import org.entrystore.rest.it.util.EntryStoreClient
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.utility.MountableFile
import spock.lang.Shared

// Child Spec classes of this base must have a Zzz* prefix so Failsafe's alphabetical runOrder
// schedules them after all shared-app ITs. The shared Spring Boot app runs for all shared-app
// ITs first; each lifecycle-owning IT then closes it and starts its own Spring Boot with
// SSO-specific args, reusing this single Keycloak container. See ENTRYSTORE-1019.
abstract class KeycloakBaseSpec extends BaseSpec {

	// Merged realm test-realm-keycloak.json contains both SAML and CAS clients;
	// the CAS protocol jar is mounted so both flows are available from one container.
	@Shared
	static KeycloakContainer keycloakContainer = new KeycloakContainer()
		.withAdminUsername('admin')
		.withAdminPassword('admin')
		.withRealmImportFile('test-realm-keycloak.json')
		.withEnv('KC_DB', 'dev-file')
		.withCopyFileToContainer(
			MountableFile.forClasspathResource('libs/keycloak-protocol-cas-26.5.6.jar'),
			'/opt/keycloak/providers/keycloak-protocol-cas.jar'
		)

	static void startKeycloakIfNeeded() {
		if (keycloakContainer.isRunning()) {
			log.info('Reusing Keycloak container at: {}:{}',
				keycloakContainer.getHost(), keycloakContainer.getMappedPort(8080))
			return
		}
		log.info('Starting Keycloak container')
		keycloakContainer.start()
		keycloakContainer.followOutput(new Slf4jLogConsumer(log))
		log.info('Started Keycloak container at: {}:{}',
			keycloakContainer.getHost(), keycloakContainer.getMappedPort(8080))
	}

	static String getKeycloakSamlRealmUrl() {
		return keycloakContainer.getAuthServerUrl() + '/realms/test/protocol/saml'
	}

	static String getKeycloakCasRealmUrl() {
		return keycloakContainer.getAuthServerUrl() + '/realms/test/protocol/cas'
	}

	protected static void stopPreexistingAppIfRunning() {
		if (appInstance != null) {
			log.info('Stopping pre-existing ES instance')
			try {
				appInstance.close()
			} finally {
				appInstance = null
				EntryStoreClient.cleanCookies()
			}
		}
	}
}
