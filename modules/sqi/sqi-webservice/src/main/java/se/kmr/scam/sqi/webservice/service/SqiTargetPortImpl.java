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

package se.kmr.scam.sqi.webservice.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

//import javax.jws.WebMethod;
//import javax.jws.WebParam;
//import javax.jws.WebResult;
//import javax.jws.WebService;
//import javax.jws.soap.SOAPBinding;
//import javax.jws.soap.SOAPBinding.ParameterStyle;
//import javax.xml.bind.annotation.XmlSeeAlso;
//import javax.xml.ws.RequestWrapper;
//import javax.xml.ws.ResponseWrapper;

import be.cenorm.isss.ltws.wsdl.sqiv1p0.AsynchronousQuery;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.FaultCodeType;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SQIFault;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetMaxDuration;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetMaxQueryResults;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetQueryLanguage;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetResultsFormat;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetResultsSetSize;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SetSourceLocation;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SQIFaultType;
import be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort;

import se.kmr.scam.sqi.query.QueryLanguageType;
import se.kmr.scam.sqi.query.QueryManagerFactory;
import se.kmr.scam.sqi.result.ResultFormatFactory;
import se.kmr.scam.sqi.result.ResultFormatType;
import se.kmr.scam.sqi.session.SqiSession;
import se.kmr.scam.sqi.session.SessionExpiredException;
import se.kmr.scam.sqi.translate.TranslationException;


/**
 * This class was generated by Apache CXF 2.1.4
 * Thu Feb 12 22:09:41 CET 2009
 * Generated source version: 2.1.4
 * 
 */

@javax.jws.WebService(
                      serviceName = "SqiTargetService",
                      portName = "SqiTargetPort",
                      targetNamespace = "urn:www.cenorm.be/isss/ltws/wsdl/SQIv1p0",
                      //wsdlLocation = "file:/home/micke/development/workspace/scam4-trunk/modules/sqi/sqi-webservice/src/main/webapp/WEB-INF/wsdl/sqiTarget.wsdl",
                      endpointInterface = "be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort")
/**
 *
 * 
 * @author Mikael Karlsson (mikael.karlsson@educ.umu.se) 
 *
 */                      
public class SqiTargetPortImpl implements SqiTargetPort {

	private static Logger log = LoggerFactory.getLogger(SqiTargetPortImpl.class);

    /**
     * 
     * This method allows the source to control the format of the results returned by the target. The
	 * format according to which the results shall be formatted is specified in the resultsFormat
     * parameter. The parameter is provided via a URI (e.g., the LOM XML Schema definitions files
     * are available at http://standards.ieee.org/reading/ieee/downloads/LOM/lomv1.0/) or via pre-
     * defined values that are case-insensitive.
	 *
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#setResultsFormat(be.cenorm.isss.ltws.wsdl.sqiv1p0.SetResultsFormat  setResultsFormat )*
     */
    public void setResultsFormat(SetResultsFormat setResultsFormat) throws SQIFault    { 
    	log.info("setResultsFormat:resultsFormat=" + setResultsFormat.getResultsFormat() + ",sessionID=" + setResultsFormat.getTargetSessionID());
    	
    	String rf = setResultsFormat.getResultsFormat();
    	try {
        	SqiSession session = SqiSession.getSession(setResultsFormat.getTargetSessionID());
        	if (rf != null && ResultFormatFactory.getResultFormatType(rf) != null) {
        		session.setParameter("resultsFormat", rf);
        		return;
        	}
    	} catch (SessionExpiredException e) {
    		log.debug("setResultsFormat:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00013);
    		fault.setMessage("No such session");
    		throw new SQIFault(e.getMessage(), fault);
        } catch (Exception e) {
    		log.error("setResultsFormat:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00001);
    		fault.setMessage("setResultsFormat");
    		throw new SQIFault(e.getMessage(), fault);
    	}
        
        log.debug("setResultsFormat:resultsFormat not supported");
        SQIFaultType fault = new SQIFaultType();
        fault.setSqiFaultCode(FaultCodeType.SQI_00010);
        fault.setMessage("Results format Not Supported");
        throw new SQIFault("Results format Not Supported", fault);
    }
    
    
    
    /* (non-Javadoc)
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#setMaxDuration(be.cenorm.isss.ltws.wsdl.sqiv1p0.SetMaxDuration  setMaxDuration )*
     */
    public void setMaxDuration(SetMaxDuration setMaxDuration) throws SQIFault    { 
        log.info("setMaxDuration:maxDuration=" + setMaxDuration.getMaxDuration() + ",sessionID=" + setMaxDuration.getTargetSessionID());
       
        SQIFaultType fault = new SQIFaultType();
		fault.setSqiFaultCode(FaultCodeType.SQI_00012);
		fault.setMessage("Method not supported: setMaxDuration");
		throw new SQIFault("Method not supported: setMaxDuration", fault);
        
    }
    
    
    /**
     * This method defines the maximum number of results, which a query will produce. The
	 * maximum number of query results is set to 100 by default, but can be controlled via this
	 * method. maxQueryResults must be 0 (zero) or greater. If the maximum number of query
     * results is set to 0 (zero), the source does not want to limit the number of maximum query
     * results produced.
     * 
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#setMaxQueryResults(be.cenorm.isss.ltws.wsdl.sqiv1p0.SetMaxQueryResults  setMaxQueryResults )*
     */
    public void setMaxQueryResults(SetMaxQueryResults setMaxQueryResults) throws SQIFault    { 
    	log.info("setMaxQueryResults:maxQueryResults=" + setMaxQueryResults.getMaxQueryResults() + ",sessionID=" + setMaxQueryResults.getTargetSessionID());
       
    	
    	try {
    		SqiSession session = SqiSession.getSession(setMaxQueryResults.getTargetSessionID());
    		int maxQueryResults = setMaxQueryResults.getMaxQueryResults();
    		if (maxQueryResults >= 0) {
    			session.setParameter("maxQueryResults", new Integer(maxQueryResults).toString());
    			return;
    		} 
    	} catch (SessionExpiredException e) {
    		log.debug("setQueryLanguage:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00013);
    		fault.setMessage("No such session");
    		throw new SQIFault(e.getMessage(), fault);
    	} catch (Exception e) {
    		log.error("setQueryLanguage:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00001);
    		fault.setMessage("setQueryLanguage");
    		throw new SQIFault(e.getMessage(), fault);
    	} 
    	
    	log.debug("setMaxQueryResults:Invalid max_query_results");
		SQIFaultType fault = new SQIFaultType();
		fault.setSqiFaultCode(FaultCodeType.SQI_00007);
		fault.setMessage("Invalid max_query_results");
		throw new SQIFault("Invalid max_query_results", fault);
		

    }

    /**
     * 
     * This method allows the source to control the syntax used in the query statement by identifying
     * the query language. Values for the parameter queryLanguageID are case-insensitive.
     *
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#setQueryLanguage(be.cenorm.isss.ltws.wsdl.sqiv1p0.SetQueryLanguage  setQueryLanguage )*
     */
    public void setQueryLanguage(SetQueryLanguage setQueryLanguage) throws SQIFault    { 
    	log.info("setQueryLanguage:queryLanguageID="+setQueryLanguage.getQueryLanguageID()+",sessionID="+setQueryLanguage.getTargetSessionID());
    	
    	try {
    		SqiSession session = SqiSession.getSession(setQueryLanguage.getTargetSessionID());
    		String queryLanguageID = setQueryLanguage.getQueryLanguageID();
    		if (QueryManagerFactory.getQueryLanguageType(queryLanguageID) != null) {
    			session.setParameter("queryLanguage", queryLanguageID);
    			return;
    		} 
    	} catch (SessionExpiredException e) {
    		log.debug("setQueryLanguage:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00013);
    		fault.setMessage("No such session");
    		throw new SQIFault(e.getMessage(), fault);
    	} catch (Exception e) {
    		log.error("setQueryLanguage:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00001);
    		fault.setMessage("setQueryLanguage");
    		throw new SQIFault(e.getMessage(), fault);
    	} 
    	
    	log.debug("setQueryLanguage:queryLanguageID not supported");
		SQIFaultType fault = new SQIFaultType();
		fault.setSqiFaultCode(FaultCodeType.SQI_00011);
		fault.setMessage("Query Language Not Supported");
		throw new SQIFault("Query Language Not Supported", fault);
    	
    }
   

    /**
     * 
     * This method returns the total number of available results of a query. The targetSessionID
	 * identifies the session. The query is provided via the queryStatement parameter 
	 *
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#getTotalResultsCount(java.lang.String  targetSessionID ,)java.lang.String  queryStatement )*
     */
    public int getTotalResultsCount(java.lang.String targetSessionID, java.lang.String queryStatement) throws SQIFault    { 
    	log.info("getTotalResultsCount:sessionID= " + targetSessionID  + ",query=" + queryStatement);
        try {
        	SqiSession session = SqiSession.getSession(targetSessionID);
            String queryLanguage = session.getParameter("queryLanguage");
            QueryLanguageType qt = QueryManagerFactory.getQueryLanguageType(queryLanguage);
    		if (qt != null) {
    			int count = QueryManagerFactory.getQueryManagerImpl(qt).count(queryStatement);
    			int maxQueryResults = new Integer(session.getParameter("maxQueryResults")).intValue();
    			if (maxQueryResults <= 0 || count <= maxQueryResults ) {
    				return count;  
    			} else {
    				return maxQueryResults;
    			}
    			
    		}    		
        } catch (SessionExpiredException e) {
        	log.debug("getTotalResultsCount:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00013);
    		fault.setMessage("No such session");
    		throw new SQIFault(e.getMessage(), fault);
        } catch (Exception e) {
    		log.error("getTotalResultsCount:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00001);
    		fault.setMessage("getTotalResultsCount");
    		throw new SQIFault(e.getMessage(), fault);
    	}
        
        log.debug("getTotalResultsCount:Query Language Not Supported");
		SQIFaultType fault = new SQIFaultType();
		fault.setSqiFaultCode(FaultCodeType.SQI_00011);
		fault.setMessage("Query Language Not Supported");
		throw new SQIFault("Query Language Not Supported", fault);
    }
  
    
    /**
     * 
     * This method defines the maximum number of results, which will be returned by a single
	 * results set. The size of the results set is set to 25 records by default, but can be controlled via
	 * this method. resultsSetSize must be 0 (zero) or greater. A source asks for all results when the
     * maximum number of results is set to 0 (zero).
	 *
	 *
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#setResultsSetSize(be.cenorm.isss.ltws.wsdl.sqiv1p0.SetResultsSetSize  setResultsSetSize )*
     */
    public void setResultsSetSize(SetResultsSetSize setResultsSetSize) throws SQIFault    { 
        log.info("setResultsSetSize:resultsSetSize=" + setResultsSetSize.getResultsSetSize() + ",sessionID=" + setResultsSetSize.getTargetSessionID());

        try {
        	SqiSession session = SqiSession.getSession(setResultsSetSize.getTargetSessionID());
        
        	if (setResultsSetSize.getResultsSetSize() >= 0) {
        		session.setParameter("resultsSetSize", "" + setResultsSetSize.getResultsSetSize());
        		return;
        	} 
        } catch (SessionExpiredException e) {
        	log.debug("getTotalResultsCount:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00013);
    		fault.setMessage("No such session");
    		throw new SQIFault(e.getMessage(), fault);
        } catch (Exception e) {
    		log.error("setResultsSetSize:", e);
    		SQIFaultType fault = new SQIFaultType();
    		fault.setSqiFaultCode(FaultCodeType.SQI_00001);
    		fault.setMessage("setResultsSetSize");
    		throw new SQIFault(e.getMessage(), fault);
    	}
        
        log.debug("setResultsSetSize:resultsSetSize not supported");
		SQIFaultType fault = new SQIFaultType();
		fault.setSqiFaultCode(FaultCodeType.SQI_00005);
		fault.setMessage("resultsSetSize not supported");
		throw new SQIFault("resultsSetSize not supported", fault);
    }
    
    
    /* (non-Javadoc)
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#setSourceLocation(be.cenorm.isss.ltws.wsdl.sqiv1p0.SetSourceLocation  setSourceLocation )*
     */
    public void setSourceLocation(SetSourceLocation setSourceLocation) throws SQIFault    { 
    	log.info("setSourceLocation:sourceLocation=" + setSourceLocation.getSourceLocation() + ",sessionID=" + setSourceLocation.getTargetSessionID());
        
    	SQIFaultType fault = new SQIFaultType();
		fault.setSqiFaultCode(FaultCodeType.SQI_00012);
		fault.setMessage("Method not supported: setSourceLocation");
		throw new SQIFault("Method not supported: setSourceLocation", fault);
    }

    /* (non-Javadoc)
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#asynchronousQuery(be.cenorm.isss.ltws.wsdl.sqiv1p0.AsynchronousQuery  asynchronousQuery )*
     */
    public void asynchronousQuery(AsynchronousQuery asynchronousQuery) throws SQIFault    { 
    	log.info("asynchronousQuery:queryID=" + asynchronousQuery.getQueryID() + ",query=" + asynchronousQuery.getQueryStatement() + ",sessionID=" + asynchronousQuery.getTargetSessionID());
        
    	SQIFaultType fault = new SQIFaultType();
		fault.setSqiFaultCode(FaultCodeType.SQI_00012);
		fault.setMessage("Method not supported: asynchronousQuery");
		throw new SQIFault("Method not supported: asynchronousQuery", fault);
    }

    /* 
     *
     * This method places a query at the target. The query statement is provided via the
	 * queryStatement parameter. Within a session identified via targetSessionID multiple queries
	 * can be submitted simultaneously. The method returns a set of metadata records matching the
	 * query. The startResult parameter identifies the start record of the results set. The index of the
	 * result set size starts with 1. The number of results returned is controlled by
	 * setResultsSetSize and its default value. A valid number for startResult can range from
	 * 1 to the total number of results. The total number of results produced is limited by
	 * setMaxQueryResults and its default value.
	 *
     * @see be.cenorm.isss.ltws.wsdl.sqiv1p0.SqiTargetPort#synchronousQuery(java.lang.String  targetSessionID ,)java.lang.String  queryStatement ,)int  startResult )*
     */
    public java.lang.String synchronousQuery(java.lang.String targetSessionID, java.lang.String queryStatement, int startResult) throws SQIFault    { 
       log.info("synchronousQuery:query=" + queryStatement + ",sessionID=" + targetSessionID  + ",startResult=" + Integer.toString(startResult));
       SqiSession session;
       try {
    	  session = SqiSession.getSession(targetSessionID);
       }  catch (SessionExpiredException e) {
       	    log.debug("synchronousQuery:", e);
      			SQIFaultType fault = new SQIFaultType();
      			fault.setSqiFaultCode(FaultCodeType.SQI_00013);
      			fault.setMessage("No such session");
      			throw new SQIFault(e.getMessage(), fault);
       }    
       if (startResult < 1) {
    		  log.debug("synchronousQuery:An invalid number is provided for startResult");
       		  SQIFaultType fault = new SQIFaultType();
       		  fault.setSqiFaultCode(FaultCodeType.SQI_00003);
       		  fault.setMessage("An invalid number is provided for startResult");
       		  throw new SQIFault("An invalid number is provided for startResult", fault);
       }
       
       String resultsFormat = session.getParameter("resultsFormat");
       ResultFormatType resultsFormatType = ResultFormatFactory.getResultFormatType(resultsFormat);
       
       String queryLanguage = session.getParameter("queryLanguage");
       QueryLanguageType queryLanguageType = QueryManagerFactory.getQueryLanguageType(queryLanguage);
       
       int resultsSetSize = Integer.parseInt(session.getParameter("resultsSetSize"));
     
       int maxQueryResults = Integer.parseInt(session.getParameter("maxQueryResults"));
       
       String result = null;
       try {
    	   result = QueryManagerFactory.getQueryManagerImpl(queryLanguageType).query(queryStatement, startResult, resultsSetSize, maxQueryResults, resultsFormatType);
       } catch	(TranslationException e) {
   	    	log.debug("synchronousQuery:", e);
 			SQIFaultType fault = new SQIFaultType();
 			fault.setSqiFaultCode(FaultCodeType.SQI_00004);
 			fault.setMessage("Invalid Query statement");
 			throw new SQIFault(e.getMessage(), fault);
       } catch (Exception e) { 
   			log.error("synchronousQuery:", e);
   			SQIFaultType fault = new SQIFaultType();
   			fault.setSqiFaultCode(FaultCodeType.SQI_00001);
   			fault.setMessage("synchronousQuery");
   			throw new SQIFault(e.getMessage(), fault);
       }
       if (result == null) {
    	   log.debug("synchronousQuery:no more_results");   
           SQIFaultType fault = new SQIFaultType();
    	   fault.setSqiFaultCode(FaultCodeType.SQI_00012);
    	   fault.setMessage("No more results");
    	   throw new SQIFault("No more results", fault);
       }
           
       return result;
           
       
    }
    
}
