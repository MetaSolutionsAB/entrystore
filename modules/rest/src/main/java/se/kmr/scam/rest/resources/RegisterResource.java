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

package se.kmr.scam.rest.resources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Date;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.MimeMessage;

import net.tanesha.recaptcha.ReCaptchaImpl;
import net.tanesha.recaptcha.ReCaptchaResponse;

import org.json.JSONException;
import org.json.JSONObject;
import org.openrdf.model.Graph;
import org.openrdf.model.ValueFactory;
import org.openrdf.model.impl.ValueFactoryImpl;
import org.restlet.data.MediaType;
import org.restlet.representation.Representation;
import org.restlet.representation.StringRepresentation;
import org.restlet.representation.Variant;
import org.restlet.resource.Post;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import se.kmr.scam.repository.AuthorizationException;
import se.kmr.scam.repository.BuiltinType;
import se.kmr.scam.repository.ContextManager;
import se.kmr.scam.repository.Entry;
import se.kmr.scam.repository.Group;
import se.kmr.scam.repository.PrincipalManager;
import se.kmr.scam.repository.PrincipalManager.AccessProperty;
import se.kmr.scam.repository.RepositoryException;
import se.kmr.scam.repository.User;
import se.kmr.scam.rest.ScamApplication;

@Deprecated
public class RegisterResource extends BaseResource {

	public static final String NSDCTERMS = "http://purl.org/dc/terms/";
	public static final String NSbase = "http://scam.sf.net/schema#";
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

	static Logger log = LoggerFactory.getLogger(RegisterResource.class);
	ScamApplication scamApp;

	public RegisterResource() {
		getVariants().add(new Variant(MediaType.APPLICATION_JSON));
		scamApp = (ScamApplication) getContext().getAttributes().get(ScamApplication.KEY);
	}

	@Post
	public void acceptRepresentation(Representation representation) {
		getPM().setAuthenticatedUserURI(getPM().getAdminUser().getURI()); 
		try {
			try {
				String s;
				try {
					s = getRequest().getEntity().getText().toString();
					log.info(s);
				} catch (IOException e) {
					getResponse().setEntity(new StringRepresentation("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></head><body>Tyv&auml;rr var det n&aring;got fel i Jsonen.</body></html>",  MediaType.TEXT_HTML));
					getPM().setAuthenticatedUserURI(getPM().getAuthenticatedUserURI());
					return;
				}

				String username = null; 
				String name = null; 
				String email = null; 
				String password = null; 
				String group = null;
				JSONObject obj = null;
				try {
					obj = new JSONObject(s);
				} catch (JSONException e) {
					obj = new JSONObject(); 
					String[] objects = s.split("&");
					for(int i = 0; i<objects.length; i++) {
						String[] spl = objects[i].split("=");
						if(spl.length==2) {
							obj.put(spl[0], spl[1]);
						}
					}
				}
				for (int i = 0; i<obj.names().length(); i++) {
					String key = (String)obj.names().get(i); 
					String value = (String)obj.get(key);	

					if(key.equals("name")) {
						name = value; 
					} else if (key.equals("email")) {
						email = value; 
					} else if (key.equals("username")) {
						username = value; 
					} else if(key.equals("password1")) {
						password = value; 
					} else if(key.equals("group")) {
						group = value; 
					} else if(key.equals("recaptcha_challenge_field")) {
						String challenge = value; 	
						String response = (String)obj.get("recaptcha_response_field");	
						ReCaptchaImpl r = new ReCaptchaImpl();
						r.setIncludeNoscript(false);
						r.setPrivateKey("6Lcz8gYAAAAAAD-rB9K9uYrh5d94sQ1Px6_qmByK");
						r.setPublicKey("6Lcz8gYAAAAAADD5bzAooblcZ1_Diq2urOfxG0i1");
						r.setRecaptchaServer(ReCaptchaImpl.HTTPS_SERVER);
						ReCaptchaResponse reponse = null; 
						try {
							reponse = r.checkAnswer("portfolio.iml.umu.se", challenge, response);
						} catch (Exception e) {
							getPM().setAuthenticatedUserURI(getPM().getAuthenticatedUserURI()); 
							getResponse().setEntity(new StringRepresentation("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></head><body>Tyv&auml;rr skrev du fel vid veriferingen, var god f&ouml;rs&ouml;k igen.</body></html>",  MediaType.TEXT_HTML));
							return; 
						}
						if(reponse.isValid() == false ) {
							getResponse().setEntity(new StringRepresentation("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></head><body>Tyv&auml;rr skrev du fel vid veriferingen, var god f&ouml;rs&ouml;k igen.</body></html>",  MediaType.TEXT_HTML));
							getPM().setAuthenticatedUserURI(getPM().getAuthenticatedUserURI());
							return; 
						}
					}
				}

				createStudent(getPM(), getCM(), name, email, username, group, password); 

				String emailMsgTxt = "Information som kan vara bra att spara.\n" +
				"Portf\u00f6lj: http://portfolio.iml.umu.se/au1\n Anv\u00e4ndarnamn: "+username+"\n L\u00f6senord: "+password;
				String emailSubjectTxt = "Konto skapat f\u00f6r AU1 portf\u00f6lj";
				String emailFromAddress = "no_reply@portfolio.iml.umu.se";
				String[] sendTo = {email};
				try {
					postMail(email, emailSubjectTxt,emailMsgTxt, emailFromAddress, username, password);
					//new GMailSender().sendSSLMessage(sendTo, emailSubjectTxt, emailMsgTxt, emailFromAddress);
				} catch (MessagingException e) {
					e.printStackTrace();
				}
			} catch (JSONException e1) {
				getResponse().setEntity(new StringRepresentation("<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"></head><body>Tyv&auml;rr kunde inte JSON skapas.</body></html>",  MediaType.TEXT_HTML));
				getPM().setAuthenticatedUserURI(getPM().getAuthenticatedUserURI());
				return;
			} 

		} catch (AuthorizationException e) {
			unauthorizedPOST();
		}
		getPM().setAuthenticatedUserURI(getPM().getAuthenticatedUserURI());
		StringBuilder stringBuilder = new StringBuilder();

		stringBuilder.append("<html>");
		stringBuilder
		.append("<head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"><title>Registrerad</title></head>");
		stringBuilder.append("<body>");
		stringBuilder.append("<h1>Grattis nu &auml;r du registrerad</h1>");
		stringBuilder.append("<div><p>");
		stringBuilder.append("Du &auml;r nu registerad och kommer inom kort att f&aring; ett bekr&auml;ftelse mail med " +
		"kontouppgifter till <a href=\"http://portfolio.iml.umu.se/au1\">portfolio.iml.umu.se/au1</a>");
		stringBuilder.append("</p></div>");
		stringBuilder.append("</body>");
		stringBuilder.append("</html>");

		getResponse().setEntity(new StringRepresentation(stringBuilder
				.toString(), MediaType.TEXT_HTML));

	}

	private  void createStudent(PrincipalManager pm, ContextManager cm, String name, String email, String username, String group, String password) {
		System.out.println("username: " + username);
		System.out.println("group: " + group);
		group = group.replace('+', ' ');
		Entry userEntry = pm.createResource(BuiltinType.User, null, null); 
		setStudentMetadata(userEntry, "Student "+ name, "student", name, email, group); 
		pm.setPrincipalName(userEntry.getResourceURI(), name);
		User u = (User)userEntry.getResource();  
		u.setSecret(password); 
		u.setLanguage("Swedish"); 
		u.setName(username);
		userEntry.setResourceURI(u.getURI()); 

		Entry contextEntry = cm.createResource(BuiltinType.Context, null, null); 
		contextEntry.addAllowedPrincipalsFor(AccessProperty.Administer, u.getURI());
		setStudentMetadata(contextEntry, name + "s portfolio", "Student portfolio", null, null, null);


		se.kmr.scam.repository.Context contextResource = (se.kmr.scam.repository.Context) contextEntry.getResource();

		Entry top = contextResource.get("_top"); 
		Entry folderEntry1 = contextResource.createResource(BuiltinType.List, null, top.getResourceURI()); 		
		setStudentMetadata(folderEntry1, "Arbetsportfolio", null, null, null, null); 

		Entry folderEntry2 = contextResource.createResource(BuiltinType.List, null, top.getResourceURI()); 		
		setStudentMetadata(folderEntry2, "Redovisningsportfolio", null, null, null, null); 

		Entry folderEntry3 = contextResource.createResource(BuiltinType.List, null, top.getResourceURI()); 		
		setStudentMetadata(folderEntry3, "Utv\u00e4rderingsportfolio", null, null, null, null); 

		cm.setContextAlias(contextEntry.getEntryURI(), name );
		u.setHomeContext(contextResource); 

		int i = 0; 
		for(URI uri : pm.getGroupUris()) {
			Group g = pm.getGroup(uri); 
			String groupName = g.getName();

			if (groupName != null) {
				if(groupName.equals("Teacher Group")) {
					contextEntry.addAllowedPrincipalsFor(AccessProperty.Administer, g.getURI());
					if ((++i)>=3) { break; } else { continue; }  
				}

				if(groupName.equals("_users")) {
					folderEntry1.addAllowedPrincipalsFor(AccessProperty.ReadResource, g.getURI());
					folderEntry1.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, g.getURI());
					folderEntry3.addAllowedPrincipalsFor(AccessProperty.ReadResource, g.getURI());
					folderEntry3.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, g.getURI());
					contextEntry.addAllowedPrincipalsFor(AccessProperty.ReadResource, getPM().getGuestUser().getEntry().getEntryURI());
					contextEntry.addAllowedPrincipalsFor(AccessProperty.ReadMetadata, getPM().getGuestUser().getEntry().getEntryURI());


					if ((++i)>=3) { break; } else { continue; }  
				}


				if(groupName.equals(group)) {
					g.addMember(u); 

					if ((++i)>=3) { break; } else { continue; }  
				}
			}
			
		}
	}

	public void postMail( String recipient, String subject, String message , String from, String username, String password) throws MessagingException {
		boolean debug = true;

		// TODO: fix fulhack!
		recipient = recipient.replaceAll("\\%40", "@");
		System.err.println("Email: " + recipient);
		
		message = "Du har nu blivit registerad\n\n" + message;
        
		//Set the host smtp address
		Properties props = new Properties();
		props.put("mail.smtp.host", "localhost");
		props.put("mail.smtp.port", "25");
		props.put("mail.transport.protocol", "smtp");
        props.put("mail.mime.charset", "UTF-8");
        Session session = Session.getDefaultInstance(props, null);
        session.setDebug(debug);
        StringBuffer mailContent = new StringBuffer();
        mailContent.append("To: ").append(recipient).append("\n");
        mailContent.append("From: ").append(from).append("\n");
        mailContent.append("Subject: ").append(subject).append("\n");
        mailContent.append("Content-type: text/plain; charset=UTF-8").append("\n");
     //   mailContent.append("Content-Transfer-Encoding: 8bit").append("\n");
        mailContent.append("\n");
        mailContent.append(message);
    
        
        try {
        	ByteArrayInputStream bais = new ByteArrayInputStream(mailContent.toString().getBytes("UTF-8"));
        	MimeMessage msg = new MimeMessage(session, bais);
			msg.setSentDate(new Date());
			Transport.send(msg);
        } catch (Exception e) {
        	e.printStackTrace();
        }
		
	} 

	public static void setStudentMetadata(Entry entry, String title, String type, String name, String email, String group) {
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
			if (group != null) {
				graph.add(root, scam_group, vf.createLiteral(group));
			}
			entry.getLocalMetadata().setGraph(graph);
		} catch (RepositoryException e) {
			e.printStackTrace();
		}
	}


}
