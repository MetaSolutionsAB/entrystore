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


package org.entrystore.repository.test;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.impl.RepositoryManagerImpl;
import org.entrystore.impl.RepositoryProperties;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.HashSet;

import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;


public class TestSuite {

	static Logger log = LoggerFactory.getLogger(TestSuite.class);

	public static final String NSDCTERMS = "http://purl.org/dc/terms/";
	public static final String NSbase = "http://entrystore.org/terms/";
	public static final IRI dc_title;
	public static final IRI dc_description;
	public static final IRI dc_subject;
	public static final IRI dc_format;

	public static final IRI scam_name;
	public static final IRI scam_email;
	public static final IRI scam_type;
	public static final IRI scam_group;


	static {
		ValueFactory vf = SimpleValueFactory.getInstance();
		dc_title = vf.createIRI(NSDCTERMS + "title");
		dc_description = vf.createIRI(NSDCTERMS + "description");
		dc_subject = vf.createIRI(NSDCTERMS + "subject");
		dc_format = vf.createIRI(NSDCTERMS + "format");

		scam_name = vf.createIRI(NSbase + "name");
		scam_email = vf.createIRI(NSbase + "email");
		scam_type = vf.createIRI(NSbase + "type");
		scam_group = vf.createIRI(NSbase + "group");
	}

	/**
	 * Initializes the following contexts, users and groups:
	 * <nl><li> The users "Donald", "Mickey", and "Daisy"</li>
	 * <li> The group "Originals" consisting of Donald and Mickey.</li>
	 * <li> The contexts "duck" and "mouse".</li></nl>
	 * Where the duck context is owned (administrative rights) by both Donald and Daisy,
	 * while the mouse context is owned only by Mickey.
	 * In addition, the Originals group is given rights to work with the
	 * mickey context without being the owner (read and write access to the context resource).
	 * The duck context is readable by guests, while the mickey context is private.
	 */
	public static void initDisneySuite(RepositoryManager rm) {
		PrincipalManager pm = rm.getPrincipalManager();
		ContextManager cm = rm.getContextManager();
		URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		try {
			//Donald Duck user
			Entry donaldE = pm.createResource(null, GraphType.User, null, null);
			pm.setPrincipalName(donaldE.getResourceURI(), "Donald");
			//donaldE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			setMetadata(donaldE, "Donald Duck", "I am easily provoked and have an occasionally explosive temper, so thread carefully around me.", "duck", null, null);
			User donald = (User) donaldE.getResource();
			donald.setSecret("donaldDonald12");

			//Daisy Duck user
			Entry daisyE = pm.createResource(null, GraphType.User, null, null);
			pm.setPrincipalName(daisyE.getResourceURI(), "Daisy");
			setMetadata(daisyE, "Daisy Duck", "I am Donald's girlfriend, but I am far more sophisticated!", "duck", null, null);
			//daisyE.addAllowedPrinccontextipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			User daisy = (User) daisyE.getResource();
			daisy.setSecret("daisyDaisy34");

			//Mickey Mouse user
			Entry mickeyE = pm.createResource(null, GraphType.User, null, null);
			pm.setPrincipalName(mickeyE.getResourceURI(), "Mickey");
			setMetadata(mickeyE, "Mickey Mouse", "I am older than I look although I still speek in a famously shy, falsetto voice.", "mouse", null, null);
			//mickeyE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			User mickey = (User) mickeyE.getResource();
			mickey.setSecret("mickeyMickey56");

			//Friends of Mickey group
			Entry friendsOfMickeyE = pm.createResource(null, GraphType.Group, null, null);
			pm.setPrincipalName(friendsOfMickeyE.getResourceURI(), "friendsOfMickey");
			setMetadata(friendsOfMickeyE, "Old friends of Mickey", null, null, null, null);
			friendsOfMickeyE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			Group friendsOfMickey = (Group) friendsOfMickeyE.getResource();
			friendsOfMickey.addMember(donald);
			friendsOfMickey.addMember(mickey);

			//The duck context.
			Entry duckE = cm.createResource(null, GraphType.Context, null, null);
			setMetadata(duckE, "Donald and Daisy Duck's place", "Scrooge has a vault, we have this.", null, null, null);
			duckE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			Context duck = (Context) duckE.getResource();
			cm.setName(duckE.getResource().getURI(), "duck");
			duckE.addAllowedPrincipalsFor(AccessProperty.Administer, donald.getURI());
			duckE.addAllowedPrincipalsFor(AccessProperty.Administer, daisy.getURI());
			duckE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			duckE.addAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getGuestUser().getURI());
			donald.setHomeContext(duck);

			//The mouse context.
			Entry mouseE = cm.createResource(null, GraphType.Context, null, null);
			setMetadata(mouseE, "Mickey Mouse's place", "Mickey's creephole with old cheese and other goodies.", "mouse", null, null);
			mouseE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			Context mouse = (Context) mouseE.getResource();
			cm.setName(mouseE.getResource().getURI(), "mouse");
			mouseE.addAllowedPrincipalsFor(AccessProperty.Administer, mickey.getURI());
			mouseE.addAllowedPrincipalsFor(AccessProperty.ReadResource, friendsOfMickey.getURI());
			mouseE.addAllowedPrincipalsFor(AccessProperty.WriteResource, friendsOfMickey.getURI());
			mickey.setHomeContext(mouse);

			// User entry without metadata
			Entry emptyMd = pm.createResource(null, GraphType.User, null, null);
			log.info("Created user without metadata: " + emptyMd.getEntryURI().toString());
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
	}

	/**
	 * For testing
	 */
	public static void addTestEntriesInDisneySuite(RepositoryManager rm) {
		ContextManager cm = rm.getContextManager();
		PrincipalManager pm = rm.getPrincipalManager();
		URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		try {
			Context duck = cm.getContext("duck");
			Context mouse = cm.getContext("mouse");

			//Test resources, using lists.
			duck.createResource(null, GraphType.List, null, null);
			mouse.createResource(null, GraphType.List, null, null);
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
	}

	/**
	 * For testing
	 */
	public static void addEntriesInDisneySuite(RepositoryManager rm) {
		ContextManager cm = rm.getContextManager();
		PrincipalManager pm = rm.getPrincipalManager();
		URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());

		try {
			Context duck = cm.getContext("duck");
			Context mouse = cm.getContext("mouse");
			User Mickey = (User) pm.getPrincipalEntry("Mickey").getResource();

			//The mouse context

			//A plain Link to Daisy at wikipedia.
			Entry linkToDaisyEntry = mouse.createLink(null, URI.create("http://en.wikipedia.org/wiki/Daisy_Duck"), null);
			setMetadata(linkToDaisyEntry, "Donalds girlfriend", "Seriously Donald, you have been dating this girl for ages, isn't it time to make the move soon?", "duck", null, null);


			//A plain Link to Daisy at wikipedia.
			Entry linkToDonaldEntry = mouse.createLink(null, URI.create("http://en.wikipedia.org/wiki/Donald_Duck"), null);
			HashSet<URI> mdRead = new HashSet<>();
			mdRead.add(pm.getPrincipalEntry("Daisy").getResourceURI());
			linkToDonaldEntry.setAllowedPrincipalsFor(AccessProperty.ReadMetadata, mdRead);
			setMetadata(linkToDonaldEntry, "Donald the man", "Daisy, Donald is a really nice chap, maybe you two should get married soon?", "duck", null, null);


			//The duck context
			//----------------

			//A plain reference to mickeys user (no local metadata).
			//TODO move this to the special system entry friends list when it is introduced.
			Entry linkEntry = duck.createReference(null, Mickey.getURI(), Mickey.getEntry().getLocalMetadataURI(), null);
			linkEntry.setGraphType(GraphType.User);
			//			linkEntry.getCachedExternalMetadata().setGraph(Mickey.getEntry().getLocalMetadata().getGraph());

			Entry subListEntry = duck.createResource(null, GraphType.List, null, null); // list (folder) 1.
			setMetadata(subListEntry, "Material", "Mixed material.", null, null, null);

			//A link to the wikipedia page on the nephews.
			Entry nephews = duck.createLink(null, URI.create("http://en.wikipedia.org/wiki/Huey%2C_Dewey%2C_and_Louie"), subListEntry.getResourceURI());
			setMetadata(nephews, "Huey, Dewey, and Louie", "These are Donalds sister Dumbellas children.", "nephew", null, null);

			//A link to the wikipedia page on the family tree :-).
			Entry familyTree = duck.createLink(null, URI.create("http://en.wikipedia.org/wiki/Duck_Family_Tree"), subListEntry.getResourceURI());
			setMetadata(familyTree, "Family tree", "The duck family from Dingus to Donald, Daisy is not in there yet...", "family", null, null);

			//A picture of the fourth nephew that sometimes appears.
			Entry phooey = duck.createResource(null, GraphType.None, ResourceType.NamedResource, null);
			setMetadata(phooey, "Phooey Duck", "A mysterius fourth nephew, a freak of nature. Drawn by accident?", "nephew", null, null);

			//A picture of the fourth nephew that sometimes appears.
			//TODO upload jpeg as well.
			Entry image = duck.createResource(null, GraphType.None, ResourceType.InformationResource, null);
			setMetadata(image, "An image", "A image, remains to be uploaded.", null, "image/jpeg", null);

			/*
			removeGuestFromMetadataACL(duck.getEntry()); // mostly to test the repository listener for ACL changes
			Thread.sleep(12000);
			addGuestToMetadataACL(duck.getEntry());
			Thread.sleep(4000);
			removeGuestFromMetadataACL(duck.getEntry());
			Thread.sleep(4000);
			addGuestToMetadataACL(duck.getEntry());
			Thread.sleep(4000);
			removeGuestFromMetadataACL(duck.getEntry());
			*/
		} catch (Exception e) {
			log.error(e.getMessage());
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
	}

	public static void setMetadata(Entry entry, String title, String desc, String subj, String format, String type) {
		Model graph = entry.getLocalMetadata().getGraph();
		IRI root = iri(entry.getResourceURI().toString());
		try {
			graph.add(root, dc_title, literal(title, "en"));
			if (desc != null) {
				graph.add(root, dc_description, literal(desc, "en"));
			}
			if (subj != null) {
				graph.add(root, dc_subject, literal(subj));
			}
			if (format != null) {
				graph.add(root, dc_format, literal(format));
			}
			if (type != null) {
				graph.add(root, scam_type, literal(type));
			}
			entry.getLocalMetadata().setGraph(graph);
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}

	public static void addGuestToMetadataACL(Entry entry) {
		Model g = entry.getGraph();
		g.add(iri(entry.getLocalMetadataURI().toString()),
				RepositoryProperties.Read,
				iri(entry.getRepositoryManager().getPrincipalManager().getGuestUser().getURI().toString()),
				iri(entry.getEntryURI().toString()));
		entry.setGraph(g);
	}

	public static void removeGuestFromMetadataACL(Entry entry) {
		Model m = new LinkedHashModel(entry.getGraph());
		m.remove(iri(entry.getLocalMetadataURI().toString()),
				RepositoryProperties.Read,
				iri(entry.getRepositoryManager().getPrincipalManager().getGuestUser().getURI().toString()),
				iri(entry.getEntryURI().toString()));
		entry.setGraph(new LinkedHashModel(m));
	}

	public static void HarvesterTestSuite(RepositoryManagerImpl rm,
			PrincipalManager pm, ContextManager cm) {

		URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		try {

		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
	}

}
