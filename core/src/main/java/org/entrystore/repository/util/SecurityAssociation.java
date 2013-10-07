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

/*
 * JBoss, the OpenSource EJB server
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */

package org.entrystore.repository.util;

import java.util.ArrayList;

import javax.security.auth.Subject;

import org.entrystore.repository.User;


/**
 * The SecurityAssociation class maintains the security principal and
 * credentials. This can be done on either a singleton basis or a thread local
 * basis depending on the server property. When the server property has been set
 * to true, the security information is maintained in thread local storage. The
 * type of thread local storage depends on the
 * org.jboss.security.SecurityAssociation.ThreadLocal property. If this property
 * is true, then the thread local storage object is of type
 * java.lang.ThreadLocal which results in the current thread's security
 * information NOT being propagated to child threads. When the property is false
 * or does not exist, the thread local storage object is of type
 * java.lang.InheritableThreadLocal, and any threads spawned by the current
 * thread will inherit the security information of the current thread. Subseqent
 * changes to the current thread's security information are NOT propagated to
 * any previously spawned child threads. When the server property is false,
 * security information is maintained in class variables which makes the
 * information available to all threads within the current VM.
 * 
 * @author Daniel O'Connor (docodan@nycap.rr.com)
 * @author <a href="mailto:Scott_Stark@displayscape.com">Scott Stark</a>.
 * @version $Revision: 2 $
 */
public final class SecurityAssociation {
	private static boolean server;

	private static User principal;

	private static Object credential;

	private static Subject subject;

	private static ThreadLocal<User> threadPrincipal;

	private static ThreadLocal<Object> threadCredential;

	private static ThreadLocal<Subject> threadSubject;

	private static RunAsThreadLocalStack threadRunAsStacks = new RunAsThreadLocalStack();

	static {
		boolean useThreadLocal = false;
		/*try {
			useThreadLocal = Boolean
					.getBoolean("org.jboss.security.SecurityAssociation.ThreadLocal");
		} catch (Throwable ex) {
		}*/

		if (useThreadLocal) {
			threadPrincipal = new ThreadLocal<User>();
			threadCredential = new ThreadLocal<Object>();
			threadSubject = new ThreadLocal<Subject>();
		} else {
			threadPrincipal = new InheritableThreadLocal<User>();
			threadCredential = new InheritableThreadLocal<Object>();
			threadSubject = new InheritableThreadLocal<Subject>();
		}
	}

	/**
	 * Get the current principal information.
	 * 
	 * @return Principal, the current principal identity.
	 */
	public static User getPrincipal() {
		if (server)
			return threadPrincipal.get();
		else
			return principal;
	}

	/**
	 * Get the current principal credential information. This can be of any type
	 * including: a String password, a char[] password, an X509 cert, etc.
	 * 
	 * @return Object, the credential that proves the principal identity.
	 */
	public static Object getCredential() {
		if (server)
			return threadCredential.get();
		else
			return credential;
	}

	/**
	 * Get the current subject information.
	 * 
	 * @return Subject, the current Subject.
	 */
	public static Subject getSubject() {
		if (server) {
			return threadSubject.get();
		} else {
			return subject;
		}
	}

	/**
	 * Set the current principal information.
	 * 
	 * @param principal, the current principal identity.
	 */
	public static void setPrincipal(User principal) {
		if (server) {
			threadPrincipal.set(principal);
		} else {
			SecurityAssociation.principal = principal;
		}
	}

	/**
	 * Set the current principal credential information. This can be of any type
	 * including: a String password, a char[] password, an X509 cert, etc.
	 * 
	 * @param credential, the credential that proves the principal identity.
	 */
	public static void setCredential(Object credential) {
		if (server) {
			threadCredential.set(credential);
		} else {
			SecurityAssociation.credential = credential;
		}
	}

	/**
	 * Set the current subject information.
	 * 
	 * @param subject, the current Subject.
	 */
	public static void setSubject(Subject subject) {
		if (server) {
			threadSubject.set(subject);
		} else {
			SecurityAssociation.subject = subject;
		}
	}

	public static void clear() {
		if (server) {
			threadPrincipal.set(null);
			threadCredential.set(null);
			threadSubject.set(null);
		} else {
			SecurityAssociation.principal = null;
			SecurityAssociation.credential = null;
			SecurityAssociation.subject = null;
		}
	}

	/**
	 */
	public static void pushRunAsRole(User runAsRole) {
		threadRunAsStacks.push(runAsRole);
	}

	public static User popRunAsRole() {
		User runAsRole = threadRunAsStacks.pop();
		return runAsRole;
	}

	public static User peekRunAsRole() {
		User runAsRole = threadRunAsStacks.peek();
		return runAsRole;
	}

	/**
	 * Set the server mode of operation. When the server property has been set
	 * to true, the security information is maintained in thread local storage.
	 * This should be called to enable property security semantics in any
	 * multi-threaded environment where more than one thread requires that
	 * security information be restricted to the thread's flow of control.
	 */
	public static void setServer() {
		server = true;
	}

	/**
	 */
	private static class RunAsThreadLocalStack extends ThreadLocal<ArrayList<User>> {
		protected ArrayList<User> initialValue() {
			return new ArrayList<User>();
		}

		void push(User runAs) {
			ArrayList<User> stack = super.get();
			stack.add(runAs);
		}

		User pop() {
			ArrayList<User> stack = super.get();
			User runAs = null;
			int lastIndex = stack.size() - 1;
			if (lastIndex >= 0) {
				runAs = stack.remove(lastIndex);
			}
			return runAs;
		}

		User peek() {
			ArrayList<User> stack = super.get();
			User runAs = null;
			int lastIndex = stack.size() - 1;
			if (lastIndex >= 0) {
				runAs = stack.get(lastIndex);
			}
			return runAs;
		}
	}
}