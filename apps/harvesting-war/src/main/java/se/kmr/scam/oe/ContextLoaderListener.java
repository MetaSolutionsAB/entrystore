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

package se.kmr.scam.oe;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

/**
 * @author Hannes Ebner
 */
public class ContextLoaderListener implements ServletContextListener {

//	private static Logger log = LoggerFactory.getLogger(ContextLoaderListener.class);

	public void contextInitialized(ServletContextEvent event) {
//		ServletContext context = event.getServletContext();
//		javax.naming.Context env;
//		try {
//			env = (javax.naming.Context) new InitialContext().lookup("java:comp/env");
//			log.info("SCAM config location: " + env.lookup("scam.config"));
//		} catch (NamingException e) {
//			log.error(e.getMessage());
//		}
		
	}

	public void contextDestroyed(ServletContextEvent event) {
//		ServletContext context = event.getServletContext();
	}

}