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

package org.entrystore.rest.util;

import org.restlet.data.Language;
import org.restlet.data.MediaType;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;

/**
 * Helper class to construct HTML parts for simple pages (login/logout, etc).
 * This should be replaced with a proper templating engine when more resources
 * start returning HTML. Perhaps never.
 *
 * @author Hannes Ebner
 */
public class SimpleHTML {

	private String title;

	public SimpleHTML(String title) {
		this.title = title;
	}

	public String wrapInHtml(String simpleContent) {
		StringBuilder sb = new StringBuilder();
		sb.append(header());
		sb.append("<div>");
		sb.append(simpleContent);
		sb.append("</div>");
		sb.append(footer());
		return sb.toString();
	}

	public String header() {
		StringBuilder sb = new StringBuilder();
		sb.append("<html>\n");
		sb.append("<head>\n<title>");
		sb.append(title);
		sb.append("</title>\n</head>\n");
		sb.append("<body style=\"width:500px;margin-left:auto;margin-right:auto;font-family:verdana;font-size:10pt;\">\n");
		sb.append("<div>\n<br/>\n");
		sb.append("<p><a href=\"http://entrystore.org\"><img src=\"data:image/png;base64,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\" alt=\"EntryStore\" title=\"EntryStore\"></a></p>\n");
		sb.append("<br/>\n<h3>");
		sb.append(title);
		sb.append("</h3>\n<br/>\n");
		return sb.toString();
	}

	public String footer() {
		StringBuilder sb = new StringBuilder();
		sb.append("\n</div>\n</body>\n</html>\n");
		return sb.toString();
	}

	public Representation representation(String simpleContent) {
		return new StringRepresentation(wrapInHtml(simpleContent), MediaType.TEXT_HTML, Language.ENGLISH);
	}

}