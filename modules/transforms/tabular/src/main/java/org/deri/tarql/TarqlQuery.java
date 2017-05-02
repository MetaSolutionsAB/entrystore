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

package org.deri.tarql;

import java.util.ArrayList;
import java.util.List;

import com.hp.hpl.jena.query.Query;
import com.hp.hpl.jena.sparql.core.Prologue;
import com.hp.hpl.jena.sparql.core.Var;

public class TarqlQuery {
	public final static Var ROWNUM = Var.alloc("ROWNUM");
	
	private  Prologue prologue = null;
	private final List<Query> queries = new ArrayList<Query>();

	public TarqlQuery() {
		setPrologue(new Prologue());
	}
	
	public TarqlQuery(Query singleQuery) {
		setPrologue(singleQuery);
		addQuery(singleQuery);
	}
	
	public void addQuery(Query query) {
		queries.add(query);
	}
	
	public List<Query> getQueries() {
		return queries;
	}
	
	public void setPrologue(Prologue prologue) {
		this.prologue = prologue;
	}
	
	public Prologue getPrologue() {
		return prologue;
	}
	
	public boolean isConstructType() {
		return !queries.isEmpty() && queries.get(0).isConstructType();
	}
	
	public void makeTest() {
		for (Query q: queries) {
			if (q.isConstructType()) {
				q.setQuerySelectType();
			}
			q.setLimit(5);
		}
	}
}
