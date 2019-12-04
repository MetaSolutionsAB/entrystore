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

import org.entrystore.config.Config;
import org.entrystore.repository.config.Settings;
import org.entrystore.rest.util.SimpleHTML;
import org.restlet.data.Language;
import org.restlet.data.MediaType;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.resource.Get;
import org.restlet.resource.ResourceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Resource to construct a HTML login form.
 * 
 * @author Hannes Ebner
 */
public class LoginResource extends BaseResource {
	
	private static Logger log = LoggerFactory.getLogger(LoginResource.class);

	@Get
	public Representation represent() throws ResourceException {
		Config config = getRM().getConfiguration();
		SimpleHTML html = new SimpleHTML("Login");
		StringBuilder sb = new StringBuilder();
		sb.append(html.header());
		sb.append("<form action=\"cookie\" method=\"post\">\n");
		sb.append("Username<br/><input type=\"text\" name=\"auth_username\"><br/>\n");
		sb.append("Password<br/><input type=\"password\" name=\"auth_password\"><br/>\n");
		sb.append("<br/>\n<input type=\"submit\" value=\"Login\" />\n");
		sb.append("</form>\n");
		boolean openid = "on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID, "off"));
		if (openid) {
			boolean google = "on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID_GOOGLE, "off"));
			boolean yahoo = "on".equalsIgnoreCase(config.getString(Settings.AUTH_OPENID_YAHOO, "off"));
			if (google || yahoo) {
				sb.append("<br/>\n");
				sb.append("Login with: ");
				if (google) {
					sb.append("<a href=\"openid/google\">Google</a>\n");
				}
				if (yahoo) {
					if (google) {
						sb.append(" | ");
					}
					sb.append("<a href=\"openid/yahoo\">Yahoo!</a>\n");
				}
			}
		}
		sb.append(html.footer());

		return new StringRepresentation(sb.toString(), MediaType.TEXT_HTML, Language.ENGLISH);
	}

}