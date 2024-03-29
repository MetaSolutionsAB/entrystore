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

package org.entrystore.impl;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.XMLSchema;

import java.util.Iterator;


public class StringResource extends RDFResource{

	public StringResource(EntryImpl entry, IRI resourceURI) {
		super(entry, resourceURI);
	}

	public StringResource(EntryImpl entry, IRI resourceURI, String str) {
		super(entry, resourceURI);
		setString(str); 
	}

	public void setString(String text) {
		Model g = getGraph();
		ValueFactory vf = this.entry.repository.getValueFactory(); 
		g.clear();
		if (text != null && !text.equals("")) {
			g.add(vf.createStatement(this.entry.getSesameResourceURI(),	RDF.VALUE, vf.createLiteral(text, XMLSchema.STRING)));
		}
		this.setGraph(g); 
	}

	public String getString() {
		Iterator<Statement> stringElements = this.getGraph().filter(this.entry.getSesameResourceURI(), RDF.VALUE, null).iterator();
		while(stringElements.hasNext()) {
			return stringElements.next().getObject().stringValue(); 
		}
		return null;  
	}
}