/**
 * Copyright (c) 2007-2010
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

package se.kmr.scam.rest.resources;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.openrdf.repository.RepositoryException;
import org.restlet.Context;
import org.restlet.data.MediaType;
import org.restlet.data.Request;
import org.restlet.data.Response;
import org.restlet.data.Status;
import org.restlet.ext.fileupload.RestletFileUpload;
import org.restlet.resource.Representation;
import org.restlet.resource.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.PrincipalManager.AccessProperty;
import se.kmr.scam.repository.impl.RepositoryManagerImpl;
import se.kmr.scam.repository.transformation.SCAM2Import;
import se.kmr.scam.repository.util.FileOperations;
import se.kmr.scam.rest.ScamApplication;
import se.kmr.scam.rest.util.Util;

/**
 * This class supports the import of single contexts. If a context is imported,
 * all existing entries are thrown away.
 * 
 * @author Hannes Ebner
 */
public class ImportResource extends BaseResource {

	Logger log = LoggerFactory.getLogger(ImportResource.class);

	/** The contexts ID. */
	String contextId = null;

	/** The context object for the context */
	se.kmr.scam.repository.Context context = null;

	/** Parameters from the URL. Example: ?scam=umu&shame=kth */
	HashMap<String,String> parameters = null;
	
	private RepositoryManagerImpl rm;

	public ImportResource(Context context, Request request, Response response) {
		super(context, request, response);

		this.contextId =(String) getRequest().getAttributes().get("context-id"); 
		String remainingPart = request.getResourceRef().getRemainingPart(); 
		parameters = Util.parseRequest(remainingPart); 

		getVariants().add(new Variant(MediaType.APPLICATION_ZIP));
		getVariants().add(new Variant(MediaType.MULTIPART_FORM_DATA));
		getVariants().add(new Variant(MediaType.ALL));

		if(getCM() != null) {
			try {
				this.context = getCM().getContext(contextId);  
			} catch (NullPointerException e) {
				// not a context
				this.context = null; 
			}
		}
		
		rm = ((ScamApplication) context.getAttributes().get(ScamApplication.KEY)).getRM();
	}
	
	@Override
	public boolean allowGet() {
		return false;
	}
	
	@Override
	public boolean allowPost() {
		return true;
	}
	
	@Override
	public void acceptRepresentation(Representation r) {
		try {
			if (!getPM().getAdminUser().getURI().equals(getPM().getAuthenticatedUserURI())) {
				throw new AuthorizationException(getPM().getUser(getPM().getAuthenticatedUserURI()), context.getEntry(), AccessProperty.Administer);
			}
			
			if (context == null) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
				return;
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
					InputStream input = null;
					if (MediaType.MULTIPART_FORM_DATA.equals(getRequest().getEntity().getMediaType(), true)) {
						input = getStreamFromForm(getRequest());
					} else {
						input = r.getStream();
					}
					if (input != null) {
						FileOperations.copyFile(input, new FileOutputStream(tmpFile));
						rm.getContextManager().importContext(context.getEntry(), tmpFile);
						getResponse().setEntity("<textarea></textarea>", MediaType.TEXT_HTML);
					} else {
						log.error("Unable to import file, received invalid data");
						getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST, "Unable to import file, received invalid data");
						return;
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