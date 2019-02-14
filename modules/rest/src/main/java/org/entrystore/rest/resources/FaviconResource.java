/*
 * Copyright (c) 2007-2017 MetaSolutions AB
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

import org.apache.commons.io.FileUtils;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.FileRepresentation;
import org.restlet.representation.Representation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;


/**
 * Returns a favicon
 * 
 * @author Hannes Ebner
 */
public class FaviconResource extends BaseResource {

	private static Logger log = LoggerFactory.getLogger(FaviconResource.class);

	List<MediaType> supportedMediaTypes = new ArrayList<MediaType>();

	boolean loadingFailed = false;

	Representation faviconRep = null;
	
	@Override
	public void doInit() {
		supportedMediaTypes.add(MediaType.APPLICATION_ALL);
	}

	@Get
	public Representation represent() throws ResourceException {
		if (faviconRep == null && !loadingFailed) {
			File favicon = getFavicon("favicon.ico");
			if (favicon != null) {
				faviconRep = new FileRepresentation(favicon, MediaType.IMAGE_ICON);
			} else {
				loadingFailed = true;
			}
		}

		if (faviconRep != null) {
			return faviconRep;
		}

		getResponse().setStatus(Status.CLIENT_ERROR_NOT_FOUND);
		return null;
	}

	private File getFavicon(String fileName) {
		URL resURL = Thread.currentThread().getContextClassLoader().getResource(fileName);
		if (resURL != null) {
			return FileUtils.toFile(resURL);
		}
		log.warn("Unable to find " + fileName + " in classpath");
		return null;
	}

}