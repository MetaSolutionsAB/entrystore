package se.kmr.scam.repository;

import java.util.EventListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements Runnable to be used with Executors to make asynchronous execution
 * possible. The event object needs to be set before the executor can call the
 * run() method, otherwise the listener does not get any information about the
 * even.
 * 
 * @author Hannes Ebner
 */
public abstract class RepositoryListener implements Runnable, EventListener {
	
	private static Logger log = LoggerFactory.getLogger(RepositoryListener.class);
	
	RepositoryEventObject eventObject;

	abstract public void repositoryUpdated(RepositoryEventObject eventObject);
	
	public void setRepositoryEventObject(RepositoryEventObject eventObject) {
		this.eventObject = eventObject;
	}
	
	public RepositoryEventObject getRepositoryEventObject() {
		return eventObject;
	}
	
	public void run() {
		if (eventObject != null) {
			repositoryUpdated(eventObject);
			eventObject = null;
		} else {
			log.warn("Listener dispatched without event");
		}
	}

}