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


package se.kmr.scam.repository.jdil;
import java.util.HashMap;

import static org.junit.Assert.assertTrue;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.openrdf.model.BNode;
import org.openrdf.model.Graph;
import org.openrdf.model.Statement;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.GraphImpl;
import org.openrdf.model.impl.URIImpl;
import org.openrdf.repository.RepositoryException;
import org.openrdf.repository.RepositoryResult;
import org.openrdf.repository.sail.SailRepository;
import org.openrdf.repository.sail.SailRepositoryConnection;
import org.openrdf.sail.memory.MemoryStore;

import se.kmr.scam.jdil.JDIL;
import se.kmr.scam.jdil.Namespaces;

public class JDILTest {

	private SailRepository repository;
	private SailRepositoryConnection connection;
	private org.openrdf.model.URI root;
	private ValueFactory vf;
	private Namespaces namespaces;
	private JDIL jdil;
	private GraphImpl graph;

	@Before
	public void setup() {
		this.repository = new SailRepository(new MemoryStore());
		try {
			repository.initialize();
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
		this.vf = this.repository.getValueFactory();
		this.root = vf.createURI("http://www.example.com/root");
		try {
			this.connection = this.repository.getConnection();
			this.connection.add(this.root, this.vf.createURI("http://www.example.com/pred1"), this.vf.createLiteral("mummah"));
			this.connection.add(this.root, this.vf.createURI("http://www.example.com/pred2"), this.vf.createURI("http://www.example.com/theone"));
			BNode node = this.vf.createBNode();
			this.connection.add(this.root, this.vf.createURI("http://www.example.com/pred3"), node);
			this.connection.add(node, this.vf.createURI("http://www.example.com/language"), this.vf.createLiteral("en", this.vf.createURI("http://purl.org/dc/terms/RFC3066")));
			
			RepositoryResult<Statement> rr = this.repository.getConnection().getStatements(null, null, null, false);
			this.graph = new GraphImpl(this.vf, rr.asList());

		} catch (RepositoryException e) {
			e.printStackTrace();
		} finally {
			try {
				connection.close();
			} catch (RepositoryException e) {
				e.printStackTrace();
			}
		}
		HashMap<String, String> nses = new HashMap<String, String>();
		nses.put("ex", "http://www.example.com/");
		this.namespaces = new Namespaces(null, nses);
		this.jdil = new JDIL(this.namespaces);
	}
	
	@Test
	public void export() {
		JSONObject obj = this.jdil.exportGraphToJDIL(this.graph, this.root);
		String str;
		try {
			str = obj.toString(2);
			System.out.println(str);
		} catch (JSONException e) {
		}
	}
	@Test
	public void parse() {
		JSONObject obj = this.jdil.exportGraphToJDIL(this.graph, this.root);
		String str;
		try {
			str = obj.toString(2);
			Graph g = this.jdil.importJDILtoGraph(obj);
			assertTrue(g.size() == this.graph.size());
			System.out.println(g);
			System.out.println(this.graph);
		} catch (JSONException e) {
		}
	}

}