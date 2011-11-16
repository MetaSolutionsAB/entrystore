package se.kmr.scam.repository;

import java.util.EventObject;

import org.openrdf.model.Graph;

/**
 * @author Hannes Ebner
 */
public class RepositoryEventObject extends EventObject {
	
	RepositoryEvent event;
	
	Graph updatedGraph;

	public RepositoryEventObject(Entry source, RepositoryEvent event) {
		super(source);
		this.event = event;
	}
	
	public RepositoryEventObject(Entry source, RepositoryEvent event, Graph updatedGraph) {
		this(source, event);
		this.updatedGraph = updatedGraph;
	}
	
	public RepositoryEvent getEvent() {
		return event;
	}

	/**
	 * @return Can be null. A graph is included directly in the event object to
	 *         save the getGraph() call on the Entry object, minimizing the
	 *         repository requests.
	 */
	public Graph getUpdatedGraph() {
		return updatedGraph;
	}
	
	@Override
	public String toString() {
		return new StringBuffer(event.name()).append(",").append(source.toString()).toString();
	}

}