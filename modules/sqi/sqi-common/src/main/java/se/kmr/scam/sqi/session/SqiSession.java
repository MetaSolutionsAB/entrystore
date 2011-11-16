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

package se.kmr.scam.sqi.session;

import java.text.SimpleDateFormat;
import java.util.Hashtable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Calendar;
import java.util.Date;
/**
 *
 * 
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
 *
 */
public class SqiSession {

	private static HashSet<SqiSession> sessions = new HashSet<SqiSession>();
	private String sessionId;
		
	private static Hashtable<String, Object> defaultParameters;
	private Hashtable<String, Object> parameters;
	private Date expires;

	static {
		defaultParameters = new Hashtable<String, Object>();
		defaultParameters.put("maxQueryResults", "100");
		defaultParameters.put("resultsSetSize", "25");
		defaultParameters.put("maxDuration", "500"); //milliseconds
		defaultParameters.put("resultsFormat", "http://ltsc.ieee.org/xsd/LOM");
		defaultParameters.put("queryLanguage", "VSQL");
		defaultParameters.put("sessionExpireTime", "15"); // minute
	}

	private SqiSession(String sessionId) {
		parameters = new Hashtable<String, Object>();
		this.sessionId = sessionId;
		resetExpiration();
		setParameter("maxQueryResults", defaultParameters.get("maxQueryResults"));
		setParameter("resultsSetSize", defaultParameters.get("resultsSetSize"));
		setParameter("maxDuration", defaultParameters.get("maxDuration"));
		setParameter("resultsFormat", defaultParameters.get("resultsFormat"));
		setParameter("queryLanguage", defaultParameters.get("queryLanguage"));
	}
	
	
	  public static synchronized SqiSession newSession() {
	        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddkkmmss");
	        String prefix = sdf.format(new Date());
	        int counter;
	        for(counter = 0; sessionExist((new StringBuilder()).append(prefix).append(counter).toString()); counter++);
	        SqiSession newSession = new SqiSession((new StringBuilder()).append(prefix).append(counter).toString());
	        sessions.add(newSession);
	        return newSession;
	  }

	  public static synchronized SqiSession getSession(String session) throws SessionExpiredException {
		  Object sessionArray[] = sessions.toArray();
		  for(int i = 0; i < sessionArray.length; i++) {
			  SqiSession currentSession = (SqiSession)sessionArray[i];
			  if(currentSession.isExpired()) {
				  sessions.remove(currentSession);
				  continue;
			  }
			  if(currentSession.equals(session)) {
				  currentSession.resetExpiration();
				  return currentSession;
			  }
		  }

		  throw new SessionExpiredException(session, "Your session is expired");
	  }

	  public void resetExpiration() {
	        Calendar c = Calendar.getInstance();
	        c.add(Calendar.MINUTE, Integer.parseInt((String)defaultParameters.get("sessionExpireTime")));
	        expires = c.getTime();
	  }
	 
	  private boolean isExpired() {
	     return expires.before(Calendar.getInstance().getTime());
	  }

	  public static boolean sessionExist(String session) {
	      try {
	            getSession(session);
	      } catch(SessionExpiredException see) {
	            return false;
	      }
	      return true;
	  }
	
	  public String getParameter(String par)
	    {
	        return (String)parameters.get(par);
	    }

	    public Object getParameterAsObject(String par)
	    {
	        return parameters.get(par);
	    }

	  public void setParameter(String name, String value)
	    {
	        parameters.put(name, value);
	    }

	    public void setParameter(String name, Object value)
	    {
	        parameters.put(name, value);
	    }

	    public static void setDefaultParameter(String name, String value)
	    {
	        defaultParameters.put(name, value);
	    }

	    public static void setDefaultParameter(String name, Object value)
	    {
	        defaultParameters.put(name, value);
	    }

	  
	  
	  
	  public static void destroy(SqiSession session) {
		  sessions.remove(session);
	  }

	  public static Iterator<SqiSession> getAllSessions() {
	        return sessions.iterator();
	  }

	  public static int getNrofSessions() {
	      return sessions.size();
	  }

	  public String toString() {
	        return sessionId;
	  }

	  public boolean equals(String sessionId) {
	        return sessionId.equals(this.sessionId);
	  }

}