/*
 * Copyright (c) 2007-2014 MetaSolutions AB
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

import java.net.URI;

import java.util.ArrayList;

import java.util.HashSet;

import org.entrystore.GraphType;
import org.entrystore.Context;
import org.entrystore.ContextManager;
import org.entrystore.Entry;
import org.entrystore.Group;
import org.entrystore.PrincipalManager;
import org.entrystore.repository.RepositoryException;
import org.entrystore.repository.RepositoryManager;
import org.entrystore.ResourceType;
import org.entrystore.User;
import org.entrystore.PrincipalManager.AccessProperty;
import org.entrystore.impl.RepositoryManagerImpl;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;


public class TestSuite {

	public static final String NSDCTERMS = "http://purl.org/dc/terms/";
	public static final String NSbase = "http://entrystore.org/terms/";
	public static final org.openrdf.model.URI dc_title;
	public static final org.openrdf.model.URI dc_description;
	public static final org.openrdf.model.URI dc_subject;
	public static final org.openrdf.model.URI dc_format;

	public static final org.openrdf.model.URI scam_name;
	public static final org.openrdf.model.URI scam_email;
	public static final org.openrdf.model.URI scam_type;
	public static final org.openrdf.model.URI scam_group;


	static {
		ValueFactory vf = ValueFactoryImpl.getInstance();
		dc_title = vf.createURI(NSDCTERMS + "title");
		dc_description = vf.createURI(NSDCTERMS + "description");
		dc_subject = vf.createURI(NSDCTERMS + "subject");
		dc_format = vf.createURI(NSDCTERMS + "format");

		scam_name = vf.createURI(NSbase + "name");
		scam_email = vf.createURI(NSbase + "email");
		scam_type = vf.createURI(NSbase + "type");
		scam_group = vf.createURI(NSbase + "group");
	}

	private static void createTeacher(PrincipalManager pm, ContextManager cm, Group group, String name, String password, String email,
			ArrayList<Group> studentGroups) {
		Entry entry = pm.createResource(null, GraphType.User, null, null);
		pm.setPrincipalName(entry.getResourceURI(), name);
		setMetadata(entry, name, "teacher", null, null, "teacher");
		User u = (User) entry.getResource();
		u.setSecret(password);
		u.setName(email); 
		group.addMember(u); 
		Entry userHomeContextE = cm.createResource(null, GraphType.Context, null, null);
		userHomeContextE.addAllowedPrincipalsFor(AccessProperty.Administer, u.getURI()); 
		
		userHomeContextE.addAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getGuestUser().getEntry().getEntryURI());
		userHomeContextE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getEntry().getEntryURI());

		Context c = (Context)userHomeContextE.getResource();
		cm.setName(userHomeContextE.getEntryURI(), name);
		u.setHomeContext(c); 
		
		for(Group g : studentGroups) {
			
			Entry linkRefEntry = c.createLinkReference(null, g.getURI(), g.getEntry().getLocalMetadata().getURI(), c.get("_top").getResourceURI());
			linkRefEntry.setGraphType(GraphType.List);
			setMetadata(linkRefEntry, g.getName(), null, null, "text/html", null);
		}

//		if(supervisor) {
//			Entry groupAEntry = c.createResource(ResourceType.List, null, c.get("_top").getResourceURI());
//			setMetadata(groupAEntry, "Grupp A", "Grupp A", null, null, null); 
//			Entry groupBEntry = c.createResource(ResourceType.List, null, c.get("_top").getResourceURI());
//			setMetadata(groupBEntry, "Grupp B", "Grupp B", null, null, null); 
//			Entry groupCEntry = c.createResource(ResourceType.List, null, c.get("_top").getResourceURI());
//			setMetadata(groupCEntry, "Grupp C", "Grupp C", null, null, null); 
//			Entry groupDEntry = c.createResource(ResourceType.List, null, c.get("_top").getResourceURI());
//			setMetadata(groupDEntry, "Grupp D", "Grupp D", null, null, null); 
//			Entry groupOvikEntry = c.createResource(ResourceType.List, null, c.get("_top").getResourceURI());
//			setMetadata(groupOvikEntry, "Grupp Ö-vik", "Grupp Ö-vik", null, null, null); 
//		} else {
//			Entry supContextEntry = cm.getEntry(cm.getContextURI("Supervisor"));
//
//			Context supContext = (Context)supContextEntry.getResource(); 
//			Entry topEntry = supContext.get("_top"); 
//
//			for(URI ur : ((List)topEntry.getResource()).getChildren()) {
//				Entry e = cm.getEntry(ur);
//				
//				Iterator<Statement> iter = e.getLocalMetadata().getGraph().iterator(); 
//				while(iter.hasNext()) {
//					Statement s = iter.next(); 
//					if(s.getPredicate().stringValue().equals(dc_title.stringValue())) {
//						if(s.getObject().stringValue().equals("Grupp A")
//								|| s.getObject().stringValue().equals("Grupp B")
//								|| s.getObject().stringValue().equals("Grupp C") 
//								|| s.getObject().stringValue().equals("Grupp D") 
//								|| s.getObject().stringValue().equals("Grupp Ö-vik")) {
//							Entry linkRefEntry = c.createLinkReference(e.getResourceURI(), e.getLocalMetadataURI(), c.get("_top").getResourceURI());
//							linkRefEntry.setResourceType(ResourceType.List);
//							//linkRefEntry.getCachedExternalMetadata().setGraph(e.getLocalMetadata().getGraph());
//							setMetadata(linkRefEntry, s.getObject().stringValue(), null, null, "text/html", null);
//
//							break; 
//						}
//					}
//				}
//			}

		//}

	}

	public static void initCourseSuite(RepositoryManager rm) {
		PrincipalManager pm = rm.getPrincipalManager();
		ContextManager cm = rm.getContextManager();
		URI currentUserURI = pm.getAuthenticatedUserURI();
		pm.setAuthenticatedUserURI(pm.getAdminUser().getURI());
		
		Entry teacherGroupE = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(teacherGroupE.getResourceURI(), "Teacher Group");
		setMetadata(teacherGroupE, "Teacher Group", "Teachers of the course", null, null, null);
		Group teacherGroup = (Group) teacherGroupE.getResource();
		
		Group studenteGroupA = createStudentGroup(pm, "Grupp A", teacherGroup); 
		Group studenteGroupB= createStudentGroup(pm, "Grupp B", teacherGroup); 
		Group studenteGroupC = createStudentGroup(pm, "Grupp C", teacherGroup); 
		Group studenteGroupD = createStudentGroup(pm, "Grupp D", teacherGroup); 
		Group studenteGroupOvik = createStudentGroup(pm, "Grupp \u00f6-vik", teacherGroup); 
		
		ArrayList<Group> studentGroups = new ArrayList<Group>(); 
		studentGroups.add(studenteGroupA); 
		studentGroups.add(studenteGroupB); 
		studentGroups.add(studenteGroupC); 
		studentGroups.add(studenteGroupD); 
		studentGroups.add(studenteGroupOvik); 
		
		try{

//			// lars-ake.pennlert@svshv.umu.se
//			createTeacher(pm, cm, teacherGroup, "Supervisor","supervisorsupervisor", "supervisor", true); 


			// lars-ake.pennlert@svshv.umu.se
			createTeacher(pm, cm, teacherGroup, "Lars-\u00e5ke Pennlert","pennlertpennlert", "lars-ake.pennlert@svshv.umu.se", studentGroups); 

			// gunnar.lindstrom@svshv.umu.se
			createTeacher(pm, cm, teacherGroup, "Gunnar Lindstr\u00f6m","lindstromlindstrom", "gunnar.lindstrom@svshv.umu.se", studentGroups);

			//jan-soren.andersson@educ.umu.se
			createTeacher(pm, cm, teacherGroup, "Jan-S\u00f6ren Andersson","anderssonandersson", "jan-soren.andersson@educ.umu.se", studentGroups);

			//jarl.cederblad@educ.umu.se
			createTeacher(pm, cm, teacherGroup, "Jarl Cederblad","cederbladcederblad", "jarl.cederblad@educ.umu.se", studentGroups);

			//tomas.bergqvist@educ.umu.se
			createTeacher(pm, cm, teacherGroup, "Tomas Bergqvist","bergqvistbergqvist", "tomas.bergqvist@educ.umu.se", studentGroups);

			//
			//Lärare Ö-vik
			//krister.lindwall@educ.umu.se
			createTeacher(pm, cm, teacherGroup, "Krister Lindwal","lindwallindwal", "krister.lindwall@educ.umu.se", studentGroups);

			//ingalill.gustafsson@svshv.umu.se
			createTeacher(pm, cm, teacherGroup, "Ingalill Gustafsson","gustafssongustafsson", "ingalill.gustafsson@svshv.umu.se", studentGroups);

			createStudent(pm, cm, "Eric Johansson", "eric@editio.se", studenteGroupA, "johanssonjohansson", teacherGroup); 
			createStudent(pm, cm, "Mikael Karlsson", "mikael.karlsson@educ.umu.se", studenteGroupB, "micke123", teacherGroup);
			createStudent(pm, cm,  "Andreas Wikstr\u00f6m", "cuy@umu.se", studenteGroupOvik, "andreas123", teacherGroup);
		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}	
	}

	private static Group createStudentGroup(PrincipalManager pm, String groupName, Group teacherGroup) {
		Entry groupE = pm.createResource(null, GraphType.Group, null, null);
		pm.setPrincipalName(groupE.getResourceURI(), groupName);
		setMetadata(groupE, groupName, groupName, null, null, null);
		groupE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, teacherGroup.getURI());
		groupE.addAllowedPrincipalsFor(AccessProperty.ReadResource, teacherGroup.getURI());
		return (Group) groupE.getResource();
	}

	private static void createStudent(PrincipalManager pm, ContextManager cm, String name, String email, Group group, String password, Group teacherGroup) {

		Entry userEntry = pm.createResource(null, GraphType.User, null, null);
		setStudentMetadata(userEntry, "Student "+ name, "student", name, email); 
		pm.setPrincipalName(userEntry.getResourceURI(), name);
	
		
		User u = (User)userEntry.getResource();  
		
		u.setSecret(password); 
		u.setLanguage("Swedish"); 
		u.setName(email);
		userEntry.setResourceURI(u.getURI()); 
		group.addMember(u); 
		

		Entry contextEntry = cm.createResource(null, GraphType.Context, null, null);
		contextEntry.addAllowedPrincipalsFor(AccessProperty.Administer, u.getURI());
		contextEntry.addAllowedPrincipalsFor(AccessProperty.Administer, teacherGroup.getURI());
		
		setStudentMetadata(contextEntry, name + "s portfolio", "Student portfolio", null,null);

		Context contextResource = (Context) contextEntry.getResource();

		Entry top = contextResource.get("_top"); 
		Entry folderEntry1 = contextResource.createResource(null, GraphType.List, null, top.getResourceURI());
		setStudentMetadata(folderEntry1, "Arbetsportfolio", null, null, null); 

		Entry folderEntry2 = contextResource.createResource(null, GraphType.List, null, top.getResourceURI());
		setStudentMetadata(folderEntry2, "Redovisningsportfolio", null, null, null); 

		Entry folderEntry3 = contextResource.createResource(null, GraphType.List, null, top.getResourceURI());
		setStudentMetadata(folderEntry3, "Utv\u00e4rderingsportfolio", null, null, null); 

		cm.setName(contextEntry.getEntryURI(), name);
		u.setHomeContext(contextResource); 
			
		// TODO: cut from RegisterResource.java, is it correct?   
		int i = 0; 
		for(URI uri : pm.getGroupUris()) {
			Group g = pm.getGroup(uri); 


			if(g.getName().equals("Teacher Group")) {
				contextEntry.addAllowedPrincipalsFor(AccessProperty.Administer, g.getURI());
				if ((++i)>=3) { break; } else { continue; }  
			}

			if(g.getName().equals("_users")) {
				folderEntry1.addAllowedPrincipalsFor(AccessProperty.ReadResource, g.getURI());
				folderEntry1.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, g.getURI());
				folderEntry3.addAllowedPrincipalsFor(AccessProperty.ReadResource, g.getURI());
				folderEntry3.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, g.getURI());
				contextEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, pm.getGuestUser().getEntry().getEntryURI());
				contextEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getEntry().getEntryURI());


				if ((++i)>=3) { break; } else { continue; }  
			}


			if(g.getName().equals(group)) {
				g.addMember(u); 

				if ((++i)>=3) { break; } else { continue; }  
			}
		}
		//END TODO
		
		
		
		
		
//		Entry supContextEntry = cm.getEntry(cm.getContextURI("Supervisor"));
//
//		Context supContext = (Context)supContextEntry.getResource(); 
//		Entry topEntry = supContext.get("_top"); 
//
//		for(URI ur : ((List)topEntry.getResource()).getChildren()) {
//			Entry e = cm.getEntry(ur);
//			
//			Iterator<Statement> iter = e.getLocalMetadata().getGraph().iterator(); 
//			while(iter.hasNext()) {
//				Statement s = iter.next(); 
//				if(s.getPredicate().stringValue().equals(dc_title.stringValue())) {
//					if(s.getObject().stringValue().equals(group)) {
//						
//						Entry linkRefEntry = supContext.createLinkReference(top.getResourceURI(), top.getLocalMetadataURI(), e.getResourceURI());
//						linkRefEntry.setResourceType(ResourceType.List);
//						setMetadata(linkRefEntry, name +" portfolio", "Detta är "+name+"s portfolio", null, "text/html", null);
//
//						break; 
//					}
//				}
//			}
//		}

	}

	public static void setStudentMetadata(Entry entry, String title, String type, String name, String email) {
		Graph graph = entry.getLocalMetadata().getGraph();
		ValueFactory vf = graph.getValueFactory(); 
		org.openrdf.model.URI root = vf.createURI(entry.getResourceURI().toString());
		try {
			if (title != null) {
				graph.add(root, dc_title, vf.createLiteral(title, "swe"));
			}
			if (type != null) {
				graph.add(root, scam_type, vf.createLiteral(type, "swe"));
			}
			if (name != null) {
				graph.add(root, scam_name, vf.createLiteral(name));
			}
			if (email != null) {
				graph.add(root, scam_email, vf.createLiteral(email));
			}
		
			entry.getLocalMetadata().setGraph(graph);
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}




	/**
	 * Initializes the following contexts, users and groups:
	 * <nl><li> The users "Donald", "Mickey", and "Daisy"</li>
	 * <li> The group "Originals" consisting of Donald and Mickey.</li>
	 * <li> The contexts "duck" and "mouse".</li></nl>
	 * Where the duck context is owned (administrative rights) by both Donald and Daisy,
	 * while the mouse context is owned only by Mickey.
	 * In addition, the Orgininals group is given rights to work with the 
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
			setMetadata(donaldE, "Donald Duck", "I am easily provoked and have an occasionally explosive temper, so thread carefully around me.", null, null, null);
			User donald = (User) donaldE.getResource();
			donald.setSecret("donalddonald");

			//Daisy Duck user
			Entry daisyE = pm.createResource(null, GraphType.User, null, null);
			pm.setPrincipalName(daisyE.getResourceURI(), "Daisy");
			setMetadata(daisyE, "Daisy Duck", "I am Donald's girlfriend, but I am far more sophisticated!", null, null, null);
			//daisyE.addAllowedPrinccontextipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			User daisy = (User) daisyE.getResource();
			daisy.setSecret("daisydaisy");

			//Mickey Mouse user
			Entry mickeyE = pm.createResource(null, GraphType.User, null, null);
			pm.setPrincipalName(mickeyE.getResourceURI(), "Mickey");
			setMetadata(mickeyE, "Mickey Mouse", "I am older than I look although I still speek in a famously shy, falsetto voice.", null, null, null);		
			//mickeyE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			User mickey = (User) mickeyE.getResource();
			mickey.setSecret("mickeymickey");

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
			setMetadata(mouseE, "Mickey Mouse's place", "Mickey's creephole with old cheese and other goodies.", null, null, null);
			mouseE.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, pm.getGuestUser().getURI());
			Context mouse = (Context) mouseE.getResource();
			cm.setName(mouseE.getResource().getURI(), "mouse");
			mouseE.addAllowedPrincipalsFor(AccessProperty.Administer, mickey.getURI());
			mouseE.addAllowedPrincipalsFor(AccessProperty.ReadResource, friendsOfMickey.getURI());
			mouseE.addAllowedPrincipalsFor(AccessProperty.WriteResource, friendsOfMickey.getURI());
			mickey.setHomeContext(mouse);

			//Add mickey and originals group as contacts in duck context (as references).
			Entry contactsEntry = duck.get("_contacts");
			Entry mickeyRefE = duck.createReference(null, mickey.getURI(), mickeyE.getLocalMetadataURI(), contactsEntry.getResourceURI());
			mickeyRefE.setGraphType(GraphType.User);
			//			mickeyRefE.getCachedExternalMetadata().setGraph(mickeyE.getLocalMetadata().getGraph());
			Entry friendsRefE = duck.createReference(null, friendsOfMickey.getURI(), friendsOfMickeyE.getLocalMetadataURI(), contactsEntry.getResourceURI());
			friendsRefE.setGraphType(GraphType.Group);
			//			friendsRefE.getCachedExternalMetadata().setGraph(friendsOfMickeyE.getLocalMetadata().getGraph());



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
			//----------------
			Entry topMouseEntry = mouse.get("_top"); // list (folder) 1.
			setMetadata(topMouseEntry, "Mickeys top level folder", "Wherever I put my gloves is my home.", "mainFolder", null, null);

			//A plain Link to Daisy at wikipedia.
			Entry linkToDaisyEntry = mouse.createLink(null, URI.create("http://en.wikipedia.org/wiki/Daisy_Duck"), topMouseEntry.getResourceURI());
			setMetadata(linkToDaisyEntry, "Donalds girlfriend", "Seriously Donald, you have been dating this girl for ages, isn't it time to make the move soon?", null, null, null);


			//A plain Link to Daisy at wikipedia.
			Entry linkToDonaldEntry = mouse.createLink(null, URI.create("http://en.wikipedia.org/wiki/Donald_Duck"), topMouseEntry.getResourceURI());
			HashSet<URI> mdRead = new HashSet<URI>();
			mdRead.add(pm.getPrincipalEntry("Daisy").getResourceURI());
			linkToDonaldEntry.setAllowedPrincipalsFor(AccessProperty.ReadMetadata, mdRead);
			setMetadata(linkToDonaldEntry, "Donald the man", "Daisy, Donald is a really nice chap, maybe you two should get married soon?", null, null, null);


			//The duck context
			//----------------

			Entry topEntry = duck.get("_top");
			setMetadata(topEntry, "Duckburg", "A place where Donald and Daisy Duck and their friends live.", "mainFolder", null, null);

			//A LinkReference to the mickeys context ("mouse"). 
			//The referenced metadata is explicitly cached and additional local metadata is provided.
			Entry linkRefEntry = duck.createLinkReference(null, topMouseEntry.getResourceURI(), topMouseEntry.getLocalMetadataURI(), topEntry.getResourceURI());
			linkRefEntry.setGraphType(GraphType.List);
			//			linkRefEntry.getCachedExternalMetadata().setGraph(topMouseEntry.getLocalMetadata().getGraph());
			setMetadata(linkRefEntry, "Our old friend Mickeys place", "Useful shortcut, sorry Daisy, you are not allowed in here.", null, "text/html", null);

			//A plain reference to mickeys user (no local metadata).
			//TODO move this to the special system entry friends list when it is introduced.
			Entry linkEntry = duck.createReference(null, Mickey.getURI(), Mickey.getEntry().getLocalMetadataURI(), topEntry.getResourceURI());
			linkEntry.setGraphType(GraphType.User);
			//			linkEntry.getCachedExternalMetadata().setGraph(Mickey.getEntry().getLocalMetadata().getGraph());

			Entry subListEntry = duck.createResource(null, GraphType.List, null, topEntry.getResourceURI()); // list (folder) 1.
			setMetadata(subListEntry, "Material", "Mixed material.", null, null, null);

			//A link to the wikipedia page on the nephews.
			Entry nephews = duck.createLink(null, URI.create("http://en.wikipedia.org/wiki/Huey%2C_Dewey%2C_and_Louie"), subListEntry.getResourceURI());
			setMetadata(nephews, "Huey, Dewey, and Louie", "These are Donalds sister Dumbellas children.", null, null, null);

			//A link to the wikipedia page on the family tree :-).
			Entry familyTree = duck.createLink(null, URI.create("http://en.wikipedia.org/wiki/Duck_Family_Tree"), subListEntry.getResourceURI());
			setMetadata(familyTree, "Family tree", "The duck family from Dingus to Donald, Daisy is not in there yet...", null, null, null);

			//A picture of the fourth nephew that sometimes appears.
			Entry phooey = duck.createResource(null, GraphType.None, ResourceType.NamedResource, topEntry.getResourceURI());
			setMetadata(phooey, "Phooey Duck", "A mysterius fourth nephew, a freak of nature. Drawn by accident?", null, null, null);

			//A picture of the fourth nephew that sometimes appears.
			//TODO upload jpeg as well.
			Entry image = duck.createResource(null, GraphType.None, ResourceType.InformationResource, topEntry.getResourceURI());
			setMetadata(image, "An image", "A image, remains to be uploaded.", null, "image/jpeg", null);


			//			try {
			//				// Create Comment Entries
			//				Entry commentEntryString1 = duck.createComment(
			//						ResourceType.String,
			//						null, 
			//						null, 
			//
			//						linkEntry.getEntryURI(), 
			//						"commentsOf");
			//
			//				Entry commentEntryString2 = duck.createComment(
			//						ResourceType.String,
			//						null, 
			//						null, 
			//
			//						linkEntry.getEntryURI(),
			//						"reviewsOf"); 
			//
			//				Entry commentEntryString3 = duck.createComment(
			//						ResourceType.String,
			//						null, 
			//						null, 
			//
			//						nephews.getEntryURI(), 
			//						"commentsOf"); 
			//
			//				Entry commentEntryString4 = duck.createResource(ResourceType.String, RepresentationType.InformationResource, null);
			//
			//				Graph graph = commentEntryString4.getLocalMetadata().getGraph(); 
			//				ValueFactory vf = graph.getValueFactory(); 
			//				org.openrdf.model.URI localSourceEntryURI = vf.createURI(familyTree.getEntryURI().toString());
			//				graph.add(
			//						vf.createURI(commentEntryString4.getResourceURI().toString()), 
			//						RepositoryProperties.CommentsOn,
			//						localSourceEntryURI, 
			//						vf.createURI(commentEntryString4.getLocalMetadataURI().toString()));
			//				graph.add(
			//						vf.createURI(commentEntryString4.getResourceURI().toString()), 
			//						RepositoryProperties.ReviewsOn,
			//						localSourceEntryURI, 
			//						vf.createURI(commentEntryString4.getLocalMetadataURI().toString()));
			//
			//				commentEntryString4.getLocalMetadata().setGraph(graph);
			//				System.err.println(familyTree.getEntryURI());
			//
			//				StringResource strRes = (StringResource)commentEntryString1.getResource(); 
			//				strRes.setString("Nice entry BRO!", null); 
			//
			//
			//				StringResource strRes2 = (StringResource)commentEntryString2.getResource(); 
			//				strRes2.setString("<h1>Title</h1>", "English"); 
			//
			//				StringResource strRes3 = (StringResource)commentEntryString3.getResource(); 
			//				strRes.setString("Nice entry SIS!", "Swedish");
			//				
			//			} catch (Exception e) {
			//				e.printStackTrace(); 
			//			}

		} finally {
			pm.setAuthenticatedUserURI(currentUserURI);
		}
	}

	public static void setMetadata(Entry entry, String title, String desc, String subj, String format, String type) {
		Graph graph = entry.getLocalMetadata().getGraph();
		ValueFactory vf = graph.getValueFactory(); 
		org.openrdf.model.URI root = vf.createURI(entry.getResourceURI().toString());
		try {
			graph.add(root, dc_title, vf.createLiteral(title, "en"));
			if (desc != null) {
				graph.add(root, dc_description, vf.createLiteral(desc, "en"));
			}
			if (subj != null) {
				graph.add(root, dc_subject, vf.createLiteral(subj));
			}
			if (format != null) {
				graph.add(root, dc_format, vf.createLiteral(format));
			}
			if(type != null) {
				graph.add(root, scam_type, vf.createLiteral(type));
			}
			entry.getLocalMetadata().setGraph(graph);
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
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
