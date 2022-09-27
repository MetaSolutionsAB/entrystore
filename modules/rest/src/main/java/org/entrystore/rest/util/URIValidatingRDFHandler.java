/*
 * Copyright (c) 2007-2018 MetaSolutions AB
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

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.helpers.RDFHandlerBase;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Checks whether the supplied URIs are compatible with java.net.URI.
 * These checks are kept as light-weight as possible to not affect performance too much.
 *
 * @author Hannes Ebner
 */
public class URIValidatingRDFHandler extends RDFHandlerBase {

	@Override
	public void handleNamespace(String prefix, String uri) throws RDFHandlerException {
		try {
			new URI(uri);
		} catch (URISyntaxException e) {
			throw new RDFHandlerException(e.getMessage());
		}
	}

	@Override
	public void handleStatement(Statement st) throws RDFHandlerException {
		try {
			if (st.getSubject() instanceof IRI) {
				new URI(st.getSubject().stringValue());
			}
			new URI(st.getPredicate().stringValue());
			if (st.getObject() instanceof IRI) {
				new URI(st.getObject().stringValue());
			}
		} catch (URISyntaxException e) {
			throw new RDFHandlerException(e.getMessage());
		}
	}

}