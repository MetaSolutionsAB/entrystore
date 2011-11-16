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

package se.kmr.scam.repository.impl;

import java.util.Iterator;

import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.URI;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.StatementImpl;

import se.kmr.scam.repository.RepositoryProperties;

public class StringResource extends RDFResource{

	public StringResource(EntryImpl entry, URI resourceURI) {
		super(entry, resourceURI);
	}

	public StringResource(EntryImpl entry, URI resourceURI, String str) {
		super(entry, resourceURI);
		setString(str, null); 
	}

	public void setString(String text, String language) {
		Graph g = getGraph();
		ValueFactory vf = g.getValueFactory(); 
		g.clear(); 
		if(language==null) {

			g.add(
					new StatementImpl(
							entry.getSesameResourceURI(), 
							RepositoryProperties.body,
							vf.createLiteral(text))
			); 
		} else {
			g.add(
					new StatementImpl(
							entry.getSesameResourceURI(), 
							RepositoryProperties.body,
							vf.createLiteral(text, language))
			); 
		}

		this.setGraph(g); 
	}

	public String getString() {
		Iterator<Statement> stringElements = this.getGraph().match(null, RepositoryProperties.body, null);
		while(stringElements.hasNext()) {
			return stringElements.next().getObject().toString(); 
		}
		return null;  

	}



}
