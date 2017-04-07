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

package org.entrystore.rest.resources;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.lang.StringEscapeUtils;
import org.entrystore.rest.util.Util;
import org.restlet.data.MediaType;
import org.restlet.data.Status;
import org.restlet.representation.Representation;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.UnsupportedEncodingException;
import java.util.Iterator;
import java.util.List;

/**
 * Resource to echo a POST request body with multipart form data
 * back as escaped content inside an HTML textarea.
 *
 * @author Hannes Ebner
 */
public class EchoResource extends BaseResource {

	static Logger log = LoggerFactory.getLogger(EchoResource.class);

	@Post
	public void acceptRepresentation(Representation r) {
		if (MediaType.MULTIPART_FORM_DATA.equals(getRequest().getEntity().getMediaType(), true)) {
			try {
				List<FileItem> items = Util.createRestletFileUpload(getContext()).parseRepresentation(getRequest().getEntity());
				Iterator<FileItem> iter = items.iterator();
				if (iter.hasNext()) {
					FileItem item = iter.next();
					// We don't echo payloads bigger than 10 MB
					if (item.getSize() > 10*1024*1024) {
						getResponse().setStatus(Status.CLIENT_ERROR_REQUEST_ENTITY_TOO_LARGE);
					}
					StringBuffer escapedContent = new StringBuffer();
					escapedContent.append("<textarea>");
					try {
						escapedContent.append(StringEscapeUtils.escapeHtml(item.getString("UTF-8")));
					} catch (UnsupportedEncodingException e) {
						log.error(e.getMessage());
					}
					escapedContent.append("</textarea>");
					getResponse().setEntity(escapedContent.toString(), MediaType.TEXT_HTML);
				}
			} catch (FileUploadException e) {
				getResponse().setStatus(Status.CLIENT_ERROR_BAD_REQUEST);
			}
		} else {
			getResponse().setStatus(Status.CLIENT_ERROR_UNSUPPORTED_MEDIA_TYPE);
		}
	}

}