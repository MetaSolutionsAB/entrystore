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

package org.entrystore.repository.util;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.rio.RDFHandlerException;
import org.eclipse.rdf4j.rio.helpers.AbstractRDFHandler;

import java.util.Map;

/**
 * Adds statements to a repository. Allows modification of statements before
 * inserting.
 * 
 * @author Hannes Ebner
 */
public class InterceptingRDFInserter extends AbstractRDFHandler {
	
	private RepositoryConnection rc;
	
	private Map<String, String> namespaces;
	
	private StatementModifier modifier;
	
	public interface StatementModifier {
		
		Statement modifyStatement(Statement stmnt); 
		
	}

	public InterceptingRDFInserter(RepositoryConnection rc) {
		this(rc, null);
	}
	
	public InterceptingRDFInserter(RepositoryConnection rc, StatementModifier modifier) {
		this.rc = rc;
		this.modifier = modifier;
	}
	
	@Override
	public void handleStatement(Statement stmnt) throws RDFHandlerException {
		if (modifier != null) {
			stmnt = modifier.modifyStatement(stmnt);
		}
		
        try {
        	rc.add(stmnt);
        } catch (RepositoryException re) {
        	throw new RDFHandlerException(re);
        }
	}
	
	public StatementModifier getStatementModifier() {
		return modifier;
	}
	
	public void setStatementModifier(StatementModifier modifier) {
		this.modifier = modifier;
	}

	public Map<String, String> getNamespaces() {
		return namespaces;
	}

	@Override
	public void handleNamespace(String prefix, String uri) throws RDFHandlerException {
		if (!namespaces.containsKey(prefix)) {
			namespaces.put(prefix, uri);
		}
	}

}