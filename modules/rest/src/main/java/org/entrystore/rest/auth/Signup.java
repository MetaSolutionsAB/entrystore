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

package org.entrystore.rest.auth;

import org.entrystore.Entry;
import org.entrystore.config.Config;
import org.entrystore.repository.config.ConfigurationManager;
import org.entrystore.repository.config.Settings;
import org.entrystore.repository.util.NS;
import org.entrystore.rest.util.Email;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Calendar;

/**
 * @author Hannes Ebner
 */
public class Signup {

	private static Logger log = LoggerFactory.getLogger(Signup.class);

	public static void setFoafMetadata(Entry entry, org.restlet.security.User userInfo) {
		Graph graph = entry.getLocalMetadata().getGraph();
		ValueFactory vf = graph.getValueFactory();
		org.openrdf.model.URI resourceURI = vf.createURI(entry.getResourceURI().toString());
		String fullname = null;
		if (userInfo.getFirstName() != null) {
			fullname = userInfo.getFirstName();
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "givenName"), vf.createLiteral(userInfo.getFirstName())));
		}
		if (userInfo.getLastName() != null) {
			if (fullname != null) {
				fullname = fullname + " " + userInfo.getLastName();
			} else {
				fullname = userInfo.getLastName();
			}
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "familyName"), vf.createLiteral(userInfo.getLastName())));
		}
		if (fullname != null) {
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "name"), vf.createLiteral(fullname)));
		}
		if (userInfo.getEmail() != null) {
			graph.add(vf.createStatement(resourceURI, vf.createURI(NS.foaf, "mbox"), vf.createURI("mailto:", userInfo.getEmail())));
		}

		entry.getLocalMetadata().setGraph(graph);
	}

}