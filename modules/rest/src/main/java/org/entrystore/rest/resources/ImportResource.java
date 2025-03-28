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

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.entrystore.AuthorizationException;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.repository.transformation.SCAM2Import;
import org.entrystore.repository.util.FileOperations;
import org.restlet.Request;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.List;


/**
 * This class supports the import of single contexts. If a context is imported,
 * all existing entries are thrown away.
 *
 * @author Hannes Ebner
 */
public class ImportResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(ImportResource.class);

	@Override
	public void doInit() {

	}

	@Post
	public void acceptRepresentation(Representation r) {
		try {
			if (context == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
			}

			if (!getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				throw new AuthorizationException(getPM().getUser(getPM().getAuthenticatedUserURI()), context.getEntry(), AccessProperty.Administer);
			}

			File tmpFile = null;
			try {
				String version = this.parameters.get("version");
				if (version != null && version.equals("2")) {
					String pathToScam2BackupDir = parameters.get("backup");
					SCAM2Import s2i = new SCAM2Import(context, pathToScam2BackupDir);
					s2i.doImport();
				} else {
					tmpFile = File.createTempFile("scam_import", null);
					InputStream input;
					if (MediaType.MULTIPART_FORM_DATA.equals(getRequest().getEntity().getMediaType(), true)) {
						input = getStreamFromForm(getRequest());
					} else {
						input = getRequestEntity().getStream();
					}
					if (input != null) {
						try {
							FileOperations.copyFile(input, Files.newOutputStream(tmpFile.toPath()));
						} catch (IOException e) {
							log.error(e.getMessage(), e);
						}

						try {
							getCM().importContext(context.getEntry(), tmpFile);
						} catch (IOException e) {
							log.error(e.getMessage(), e);
						}

						getResponse().setEntity("<textarea></textarea>", MediaType.TEXT_HTML);
					} else {
						log.error("Unable to import file, received invalid data");
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Unable to import file, received invalid data");
					}
				}
			} catch (IOException e) {
				log.error(e.getMessage());
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, e.getMessage());
			} catch (RepositoryException re) {
				log.error(re.getMessage());
				getResponse().setStatus(Status.SERVER_ERROR_INTERNAL, re.getMessage());
			} finally {
				if (tmpFile != null) {
					tmpFile.delete();
				}
			}
		} catch(AuthorizationException e) {
			log.error("unauthorizedPOST");
			unauthorizedPOST();
		}
	}

	private InputStream getStreamFromForm(Request request) {
		try {
			RestletFileUpload upload = new RestletFileUpload(new DiskFileItemFactory());
			List<FileItem> items = upload.parseRequest(request);
			Iterator<FileItem> iter = items.iterator();
			if (iter.hasNext()) {
				FileItem item = iter.next();
				return item.getInputStream();
			}
		} catch (FileUploadException e) {
			log.warn(e.getMessage());
			getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
		} catch (IOException ioe) {
			log.warn(ioe.getMessage());
			getResponse().setStatus(Status.SERVER_ERROR_INTERNAL);
		}
		return null;
	}

}
