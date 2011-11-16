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

/**
 *
 * 
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
 *
 */
public class SessionExpiredException extends Exception
{
	private String sessionId;
    private String message;
    
    public SessionExpiredException(String sessionId, String message) {
        setSession(sessionId);
        setMessage(message);
    }

    public void setSession(String sessionId)
    {
    	this.sessionId = sessionId;
    }

    public String getSession()
    {
        return this.sessionId;
    }

    public void setMessage(String message)
    {
        this.message = message;
    }

    public String getMessage()
    {
        return this.message;
    }

    public String toString()
    {
        return (new StringBuilder(String.valueOf(super.toString()))).append(" \n").append("SessionId: '").append(getSession()).append("'").append(" \n").append("Message            : ").append(getMessage()).toString();
    }

    
}
