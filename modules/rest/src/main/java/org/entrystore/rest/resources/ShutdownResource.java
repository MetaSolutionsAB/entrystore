/*
 * Copyright (c) 2007-2023 MetaSolutions AB
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

package org.entrystore.rest.resources;

import java.net.URI;
import org.entrystore.PrincipalManager;
import org.entrystore.rest.EntryStoreApplication;
import org.restlet.data.Status;
import org.restlet.representation.EmptyRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shuts down the entire application.
 */
public class ShutdownResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ShutdownResource.class);

	@Post
	public void represent() throws Exception {
		PrincipalManager pm = getRM().getPrincipalManager();
		URI authUser = pm.getAuthenticatedUserURI();
		if (!pm.getAdminUser().getURI().equals(authUser) && !pm.getAdminGroup().isMember(pm.getUser(authUser))) {
			unauthorizedPOST();
			return;
		}

		if (getApplication() instanceof EntryStoreApplication application) {
			new Thread(() -> {
				getRM().shutdown();
				application.shutdownServer();
			}).start();
		} else {
			log.warn("Shutdown of server not supported, Application not instance "
							 + "of EntryStoreApplication but of [{}]", getApplication().getClass());
		}
		getResponse().setStatus(Status.SUCCESS_ACCEPTED);
	}
}
